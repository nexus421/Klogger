package bayern.kickner.klogger

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileDestinationTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        tempDir = Files.createTempDirectory("klogger-file-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        KLogger.resetForTest()
        tempDir.deleteRecursively()
    }

    @Test
    fun `logToFile creates missing parent directories and log file`() {
        val nestedLogFile = File(tempDir, "deeply/nested/dir/app.log")
        assertFalse(nestedLogFile.exists())

        KLogger.configure {
            logToFile(nestedLogFile)
        }

        assertTrue(nestedLogFile.exists(), "File should be created on initialization")
    }

    @Test
    fun `logToFile appends formatted log messages with level, tag and timestamp`() {
        val logFile = File(tempDir, "test.log")
        KLogger.configure {
            logToFile(logFile)
        }

        KLogger.info("TestTag") { "First log entry" }
        KLogger.warn("AnotherTag") { "Second log entry" }

        val lines = logFile.readLines()
        assertEquals(2, lines.size)

        // Verify format: dd.MM.yyyy HH:mm:ss LEVEL/TAG: message
        val pattern = Regex("""^\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} INFO/TestTag: First log entry$""")
        assertTrue(pattern.matches(lines[0]), "Line 1 didn't match expected pattern: ${lines[0]}")

        val pattern2 = Regex("""^\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} WARN/AnotherTag: Second log entry$""")
        assertTrue(pattern2.matches(lines[1]), "Line 2 didn't match expected pattern: ${lines[1]}")
    }

    @Test
    fun `logToFile handles multiline messages properly`() {
        val logFile = File(tempDir, "multiline.log")
        KLogger.configure {
            logToFile(logFile)
        }

        KLogger.error("CrashTag") { "Line 1\nLine 2\nLine 3" }

        val text = logFile.readText()
        assertTrue(text.contains("Line 1\nLine 2\nLine 3"))
        assertTrue(text.contains("ERROR/CrashTag: Line 1"))
    }
}
