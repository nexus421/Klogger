package bayern.kickner.klogger

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class FileBackedLogQueueTest {

    private lateinit var tempDir: File
    private lateinit var queue: FileBackedLogQueue

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("klogger-test").toFile()
        queue = FileBackedLogQueue(tempDir.path)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Makes the next enqueue() fail: a directory is pre-created at every path (with [suffix]) the
     * entry could get - counter 0, any timestamp within the next few seconds. With ".logq" the
     * rename onto the directory fails; with ".logq.tmp" already the write fails.
     */
    private fun blockNextPersist(suffix: String = ".logq") {
        val now = System.currentTimeMillis()
        val counter = 0L.toString().padStart(19, '0')
        for (t in now until now + 5000) File(tempDir, "${t}_$counter$suffix").mkdir()
    }

    @Test
    fun `maxQueueSize below 1 is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { FileBackedLogQueue(tempDir.path, maxQueueSize = 0) }
    }

    @Test
    fun `enqueue throws when the entry cannot be persisted`() {
        blockNextPersist()

        assertFailsWith<IOException> { queue.enqueue(KLogger.Level.INFO, "tag", "msg") }
    }

    @Test
    fun `a persist failure is not reported through KLogger`() {
        // Reporting through KLogger would re-enter the logger from inside a destination while
        // holding the queue lock - and recurse when the cached forwarder is itself a destination.
        val dispatched = mutableListOf<String>()
        KLogger.resetForTest()
        KLogger.configure { logToCustom { _, _, message -> dispatched.add(message) } }
        try {
            blockNextPersist()
            runCatching { queue.enqueue(KLogger.Level.INFO, "tag", "msg") }

            assertTrue(dispatched.isEmpty(), "persist failure was dispatched through KLogger: $dispatched")
        } finally {
            KLogger.resetForTest()
        }
    }

    @Test
    fun `isEmpty ignores files that are not queue entries`() {
        File(tempDir, "stale.logq.tmp").writeText("partial") // e.g. left behind by a crash mid-write
        File(tempDir, "unrelated.txt").writeText("")

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a failed write leaves no temp file behind`() {
        blockNextPersist(suffix = ".logq.tmp")
        val tmpEntriesBefore = tempDir.list()!!.count { it.endsWith(".tmp") }

        runCatching { queue.enqueue(KLogger.Level.INFO, "tag", "msg") }

        // The blocking directory that was used as the temp path must have been cleaned up
        assertEquals(tmpEntriesBefore - 1, tempDir.list()!!.count { it.endsWith(".tmp") })
    }

    @Test
    fun `line breaks in the tag do not corrupt the entry format`() {
        queue.enqueue(KLogger.Level.WARN, "line1\r\nline2", "msg")

        val (level, tag, msg) = queue.read(queue.listFiles().single())

        // The format is line-based (level / tag / message): a multi-line tag would shift the
        // message into the tag and lose data
        assertEquals(KLogger.Level.WARN, level)
        assertEquals("line1 line2", tag)
        assertEquals("msg", msg)
    }

    @Test
    fun `isEmpty returns true on a fresh queue`() {
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `isEmpty returns false after enqueue`() {
        queue.enqueue(KLogger.Level.INFO, "tag", "message")
        assertFalse(queue.isEmpty())
    }

    @Test
    fun `enqueue and read round-trips level, tag, and message correctly`() {
        queue.enqueue(KLogger.Level.WARN, "MyTag", "Hello World")

        val files = queue.listFiles()
        assertEquals(1, files.size)

        val (level, tag, msg) = queue.read(files.first())
        assertEquals(KLogger.Level.WARN, level)
        assertEquals("MyTag", tag)
        assertEquals("Hello World", msg)
    }

    @Test
    fun `multiline message is preserved after read`() {
        val multiline = "line1\nline2\nline3"
        queue.enqueue(KLogger.Level.ERROR, "tag", multiline)

        val (_, _, msg) = queue.read(queue.listFiles().first())
        assertEquals(multiline, msg)
    }

    @Test
    fun `remove deletes the file and queue becomes empty`() {
        queue.enqueue(KLogger.Level.INFO, "tag", "msg")
        val file = queue.listFiles().first()
        queue.remove(file)

        assertTrue(queue.isEmpty())
        assertEquals(0, queue.listFiles().size)
    }

    @Test
    fun `listFiles returns entries in insertion order`() {
        queue.enqueue(KLogger.Level.DEBUG, "t", "first")
        queue.enqueue(KLogger.Level.INFO, "t", "second")
        queue.enqueue(KLogger.Level.WARN, "t", "third")

        val messages = queue.listFiles().map { queue.read(it).third }
        assertEquals(listOf("first", "second", "third"), messages)
    }

    @Test
    fun `all log levels survive a round-trip`() {
        KLogger.Level.entries.forEach { level ->
            queue.enqueue(level, "tag", "msg")
        }

        val roundTrippedLevels = queue.listFiles().map { queue.read(it).first }
        assertEquals(KLogger.Level.entries.toList(), roundTrippedLevels)
    }

    @Test
    fun `insertion order is preserved once the counter reaches double digits`() {
        // Zero-padded counter must keep sorting correctly past the "9 -> 10" boundary
        val expected = (0 until 15).map { "msg$it" }
        expected.forEach { queue.enqueue(KLogger.Level.INFO, "t", it) }

        val messages = queue.listFiles().map { queue.read(it).third }
        assertEquals(expected, messages)
    }

    @Test
    fun `enqueue drops the oldest entries once maxQueueSize is exceeded`() {
        val boundedQueue = FileBackedLogQueue(tempDir.path, maxQueueSize = 3)

        repeat(5) { i -> boundedQueue.enqueue(KLogger.Level.INFO, "tag", "msg$i") }

        val messages = boundedQueue.listFiles().map { boundedQueue.read(it).third }
        assertEquals(listOf("msg2", "msg3", "msg4"), messages)
    }
}
