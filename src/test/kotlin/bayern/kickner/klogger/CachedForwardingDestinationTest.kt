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
    fun `cached messages are flushed when target recovers`() {
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

        // Second call succeeds → flushes cache + delivers new message
        shouldFail = false
        dest.log(KLogger.Level.INFO, "tag", "new msg")

        assertContains(received, "cached msg")
        assertContains(received, "new msg")
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `level and tag are preserved through cache round-trip`() {
        var shouldFail = true
        var receivedLevel: KLogger.Level? = null
        var receivedTag: String? = null
        val dest = CachedForwardingDestination(
            target = LambdaDestination { level, tag, _ ->
                if (shouldFail) throw RuntimeException("offline")
                receivedLevel = level
                receivedTag = tag
            },
            queue = queue
        )

        dest.log(KLogger.Level.CRASH, "OriginalTag", "msg")
        shouldFail = false
        dest.log(KLogger.Level.INFO, "trigger", "flush")

        assertEquals(KLogger.Level.CRASH, receivedLevel)
        assertEquals("OriginalTag", receivedTag)
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

        // One trigger call: flushes at most 2 cached + delivers 1 new
        dest.log(KLogger.Level.INFO, "tag", "trigger")

        // 2 flushed from cache + 1 new = 3 total; 2 still in cache
        assertEquals(3, received.size)
        assertFalse(queue.isEmpty())
    }
}
