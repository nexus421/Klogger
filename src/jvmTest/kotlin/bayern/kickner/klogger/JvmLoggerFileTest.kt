package bayern.kickner.klogger

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmLoggerFileTest {
    @Test
    fun writing_to_file_destination_creates_and_appends() {
        val tmpDir = createTempDir(prefix = "klogger-test-")
        val logFile = File(tmpDir, "app.log")

        Logger.configure {
            logToFile(logFile.absolutePath)
            minLevel = Logger.Level.DEBUG
            debug = true
        }

        Logger.info("JvmTest") { "hello world" }
        Logger.error("JvmTest") { "something went wrong" }

        val content = logFile.readText()
        assertTrue(content.contains("INFO/JvmTest: hello world"), "INFO line present")
        assertTrue(content.contains("ERROR/JvmTest: something went wrong"), "ERROR line present")
        // cleanup
        logFile.delete()
        tmpDir.deleteRecursively()
    }
}
