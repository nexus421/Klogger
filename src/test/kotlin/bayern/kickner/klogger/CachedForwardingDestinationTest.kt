package bayern.kickner.klogger

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CachedForwardingDestinationTest {

    private lateinit var tempDir: File
    private lateinit var queue: FileBackedLogQueue

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("klogger-test").toFile()
        queue = FileBackedLogQueue(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `successful forward is not cached`() {
        val dest = CachedForwardingDestination(
            target = LambdaDestination { _, _, _ -> },
            queue = queue
        )

        dest.log(KLogger.Level.INFO, "tag", "msg")

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `failed forward is written to cache`() {
        val dest = CachedForwardingDestination(
            target = LambdaDestination { _, _, _ -> throw RuntimeException("offline") },
            queue = queue
        )

        dest.log(KLogger.Level.INFO, "tag", "msg")

        assertFalse(queue.isEmpty())
    }

    @Test
    fun `cached messages are flushed before a newly recovered message, preserving order`() {
        var shouldFail = true
        val received = mutableListOf<String>()
        val dest = CachedForwardingDestination(
            target = LambdaDestination { _, _, message ->
                if (shouldFail) throw RuntimeException("offline")
                received.add(message)
            },
            queue = queue
        )

        // First call fails → goes to cache
        dest.log(KLogger.Level.INFO, "tag", "cached msg")
        assertTrue(received.isEmpty())
        assertFalse(queue.isEmpty())

        // Second call: target recovered, but a backlog exists → this message must not jump
        // ahead of the older cached one
        shouldFail = false
        dest.log(KLogger.Level.INFO, "tag", "new msg")

        assertEquals(listOf("cached msg", "new msg"), received)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `level and tag are preserved through cache round-trip`() {
        var shouldFail = true
        val received = mutableListOf<Triple<KLogger.Level, String, String>>()
        val dest = CachedForwardingDestination(
            target = LambdaDestination { level, tag, message ->
                if (shouldFail) throw RuntimeException("offline")
                received.add(Triple(level, tag, message))
            },
            queue = queue
        )

        dest.log(KLogger.Level.CRASH, "OriginalTag", "msg")
        shouldFail = false
        dest.log(KLogger.Level.INFO, "trigger", "flush")

        assertContains(received, Triple(KLogger.Level.CRASH, "OriginalTag", "msg"))
        assertContains(received, Triple(KLogger.Level.INFO, "trigger", "flush"))
    }

    @Test
    fun `maxFlushPerCall limits how many cached entries are forwarded per call`() {
        var shouldFail = true
        val received = mutableListOf<String>()
        val dest = CachedForwardingDestination(
            target = LambdaDestination { _, _, message ->
                if (shouldFail) throw RuntimeException("offline")
                received.add(message)
            },
            queue = queue,
            maxFlushPerCall = 2
        )

        // Cache 4 messages
        repeat(4) { i -> dest.log(KLogger.Level.INFO, "tag", "msg$i") }
        shouldFail = false

        // Backlog exists, so the trigger message is queued too (order preservation) instead of
        // being delivered directly; at most 2 of the now 5 queued entries are flushed
        dest.log(KLogger.Level.INFO, "tag", "trigger")

        assertEquals(listOf("msg0", "msg1"), received)
        assertFalse(queue.isEmpty())
    }

    @Test
    fun `concurrent log calls do not deliver cached entries twice`() {
        var shouldFail = true
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val dest = CachedForwardingDestination(
            target = LambdaDestination { _, _, message ->
                if (shouldFail) throw RuntimeException("offline")
                received.add(message)
            },
            queue = queue,
            maxFlushPerCall = 50
        )

        // Cache a batch of messages while the target is down
        repeat(20) { i -> dest.log(KLogger.Level.INFO, "tag", "msg$i") }
        assertEquals(20, queue.listFiles().size)

        shouldFail = false

        // Many threads race to trigger a flush of the same backlog concurrently
        val threads = (1..10).map { n -> Thread { dest.log(KLogger.Level.INFO, "tag", "trigger$n") } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 20 cached + 10 triggers = 30 messages total, each delivered exactly once
        assertEquals(30, received.size)
        val counts = received.groupingBy { it }.eachCount()
        assertTrue(counts.values.all { it == 1 }, "found duplicate deliveries: $counts")
        assertTrue(queue.isEmpty())
    }
}
