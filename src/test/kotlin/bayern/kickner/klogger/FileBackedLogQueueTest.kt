package bayern.kickner.klogger

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileBackedLogQueueTest {

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
        val boundedQueue = FileBackedLogQueue(tempDir, maxQueueSize = 3)

        repeat(5) { i -> boundedQueue.enqueue(KLogger.Level.INFO, "tag", "msg$i") }

        val messages = boundedQueue.listFiles().map { boundedQueue.read(it).third }
        assertEquals(listOf("msg2", "msg3", "msg4"), messages)
    }
}
