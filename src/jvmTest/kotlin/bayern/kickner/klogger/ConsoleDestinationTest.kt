package bayern.kickner.klogger

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.*

class ConsoleDestinationTest {

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        KLogger.resetForTest()
    }

    private fun captureStdoutAndStderr(block: () -> Unit): Pair<String, String> {
        val origOut = System.out
        val origErr = System.err
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true))
        System.setErr(PrintStream(errBuf, true))
        try {
            block()
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
        return Pair(outBuf.toString(), errBuf.toString())
    }

    @Test
    fun `DEBUG, INFO, WARN are written to stdout`() {
        val dest = ConsoleDestination()

        val (stdout, stderr) = captureStdoutAndStderr {
            dest.log(KLogger.Level.DEBUG, "TagD", "debug message")
            dest.log(KLogger.Level.INFO, "TagI", "info message")
            dest.log(KLogger.Level.WARN, "TagW", "warn message")
        }

        assertTrue(stdout.contains("DEBUG/TagD: debug message"))
        assertTrue(stdout.contains("INFO/TagI: info message"))
        assertTrue(stdout.contains("WARN/TagW: warn message"))
        assertTrue(stderr.isEmpty(), "stderr should be empty for DEBUG, INFO, WARN")
    }

    @Test
    fun `ERROR and CRASH are written to stderr`() {
        val dest = ConsoleDestination()

        val (stdout, stderr) = captureStdoutAndStderr {
            dest.log(KLogger.Level.ERROR, "TagE", "error message")
            dest.log(KLogger.Level.CRASH, "TagC", "crash message")
        }

        assertTrue(stderr.contains("ERROR/TagE: error message"))
        assertTrue(stderr.contains("CRASH/TagC: crash message"))
        assertTrue(stdout.isEmpty(), "stdout should be empty for ERROR and CRASH")
    }

    @Test
    fun `logToConsole configures console logging through KLogger`() {
        KLogger.configure {
            logToConsole()
        }

        val (stdout, stderr) = captureStdoutAndStderr {
            KLogger.info("SysOutTag") { "sent to out" }
            KLogger.error("SysErrTag") { "sent to err" }
        }

        assertTrue(stdout.contains("INFO/SysOutTag: sent to out"))
        assertTrue(stderr.contains("ERROR/SysErrTag: sent to err"))
    }
}
