package bayern.kickner.klogger

import bayern.kickner.klogger.http.HttpLogAppender
import bayern.kickner.klogger.http.logToHttp
import bayern.kickner.klogger.loki.LokiAppender
import bayern.kickner.klogger.loki.logToLoki
import bayern.kickner.klogger.slf4j.slf4jBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

/**
 * Pins the 0.3.0 API and output: every call form below must keep compiling unchanged, and nothing a user
 * did not opt into may change its output. If one of these tests needs to be edited, existing users break.
 */
class ApiCompatibilityTest {

    private lateinit var server: TestHttpServer
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    private val defaultLine = Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} (\w+)/(\w+): (.*)""")

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        server = TestHttpServer()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dir = Files.createTempDirectory("klogger-compat").toFile()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
        KLogger.resetForTest()
    }

    @Test
    fun `every 0_3_0 DSL call form still compiles and dispatches`() {
        val sink = mutableListOf<String>()
        val positionalSink: (KLogger.Level, String, String) -> Unit =
            { _, _, message -> sink.add("positional:$message") }
        val cachedTarget: (KLogger.Level, String, String) -> Unit = { _, _, message -> sink.add("cached:$message") }
        val appender = HttpLogAppender(
            url = server.url,
            bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
            initialFlushInterval = 1.hours,
            initialScope = scope,
        )
        val stdout = captureStdout {
            KLogger.configure {
                minLevel = KLogger.Level.DEBUG
                debug = false
                logToConsole()
                logToFile(File(dir, "app.log"))
                logToCustom(positionalSink)
                logToCustom { _, _, message -> sink.add("trailing:$message") }
                logToCachedForwarding(File(dir, "cache1").path, cachedTarget)
                logToCachedForwarding(File(dir, "cache2").path, cachedTarget, 10, 500)
                logToHttp(appender)
                logToLoki(server.url, "app", "", emptyMap(), 500, 1.hours, 50, scope)
                slf4jBridge { }
            }
            KLogger.info("T") { "hello" }
        }

        assertEquals(
            listOf("positional:hello", "trailing:hello", "cached:hello", "cached:hello"),
            sink,
        )
        assertContains(stdout, "INFO/T: hello")
        assertContains(File(dir, "app.log").readText(), "INFO/T: hello")
        appender.flush()
        LokiAppender.flush()
        assertEquals(2, server.bodies.size)
        assertEquals("hello", server.bodies[0])
        assertContains(server.bodies[1], "hello")
    }

    @Test
    fun `extension helpers keep their call forms and messages`() {
        val got = mutableListOf<Triple<KLogger.Level, String, String>>()
        KLogger.configure { logToCustom { level, tag, message -> got.add(Triple(level, tag, message)) } }
        val ex = RuntimeException("x")

        debugLog { "d" }
        infoLog { "i" }
        warnLog { "w" }
        errorLog("m")
        errorLog("m", ex)
        errorLog("m", ex, printStackTrace = false, sendAsCrash = true)
        errorLog("m", RuntimeException(), printStackTrace = false)
        errorLog(ex)
        staticLog(KLogger.Level.WARN, "Static") { "s" }

        val tag = "ApiCompatibilityTest"
        assertEquals(Triple(KLogger.Level.DEBUG, tag, "d"), got[0])
        assertEquals(Triple(KLogger.Level.INFO, tag, "i"), got[1])
        assertEquals(Triple(KLogger.Level.WARN, tag, "w"), got[2])
        assertEquals(Triple(KLogger.Level.ERROR, tag, "m"), got[3])
        assertEquals(KLogger.Level.ERROR, got[4].first)
        assertEquals("m\n" + ex.stackTraceToString(), got[4].third)
        assertEquals(Triple(KLogger.Level.CRASH, tag, "m\nException: x"), got[5])
        assertEquals(Triple(KLogger.Level.ERROR, tag, "m\nException: RuntimeException"), got[6])
        assertEquals(Triple(KLogger.Level.ERROR, tag, ex.stackTraceToString()), got[7])
        assertEquals(Triple(KLogger.Level.WARN, "Static", "s"), got[8])
    }

    @Test
    fun `file output is the default format plus line separator in UTF-8`() {
        val file = File(dir, "nested/app.log")
        KLogger.configure { logToFile(file) }

        KLogger.info("T") { "hello" }
        KLogger.error("T") { "grüße ✓" }

        val text = file.readText(Charsets.UTF_8)
        val sep = System.lineSeparator()
        assertTrue(text.endsWith(sep))
        val lines = text.removeSuffix(sep).split(sep)
        assertEquals(2, lines.size)
        assertEquals(listOf("INFO", "T", "hello"), defaultLine.matchEntire(lines[0])!!.groupValues.drop(1))
        assertEquals(listOf("ERROR", "T", "grüße ✓"), defaultLine.matchEntire(lines[1])!!.groupValues.drop(1))
    }

    @Test
    fun `existing file content is kept and appended to`() {
        val file = File(dir, "app.log").apply { writeText("old line\n") }
        KLogger.configure { logToFile(file) }

        KLogger.info("T") { "new" }

        val text = file.readText()
        assertTrue(text.startsWith("old line\n"))
        assertContains(text, "INFO/T: new")
    }

    @Test
    fun `console uses the default format and splits stdout and stderr`() {
        KLogger.configure { logToConsole() }
        lateinit var stderr: String
        val stdout = captureStdout {
            stderr = captureStderr {
                KLogger.warn("T") { "to stdout" }
                KLogger.error("T") { "to stderr" }
                KLogger.crash("T") { "crash to stderr" }
            }
        }

        assertEquals(listOf("WARN", "T", "to stdout"), defaultLine.matchEntire(stdout.trim())!!.groupValues.drop(1))
        val errLines = stderr.trim().lines()
        assertEquals(listOf("ERROR", "T", "to stderr"), defaultLine.matchEntire(errLines[0])!!.groupValues.drop(1))
        assertEquals(
            listOf("CRASH", "T", "crash to stderr"),
            defaultLine.matchEntire(errLines[1])!!.groupValues.drop(1)
        )
    }

    @Test
    fun `members of the enclosing class are not shadowed inside configure`() {
        val out = mutableListOf<String>()
        SetupWithStringFormat("JSON", out).setup()
        SetupWithFormatter(DateTimeFormatter.ofPattern("yyyy"), out).setup()

        KLogger.info("T") { "hello" }

        assertEquals(listOf("JSON|INFO|hello", "2026|INFO|hello"), out)
    }

    @Test
    fun `formatLogDefault output is unchanged`() {
        val line = KLogger.formatLogDefault(KLogger.Level.INFO, "Tag", "msg")
        assertEquals(listOf("INFO", "Tag", "msg"), defaultLine.matchEntire(line)!!.groupValues.drop(1))
    }
}

/** 0.3.0-style setup code whose own `format` property is used inside `configure { }`. */
private class SetupWithStringFormat(private val format: String, private val out: MutableList<String>) {
    fun setup() = KLogger.configure { logToCustom { level, _, message -> out.add("$format|$level|$message") } }
}

/** Same with a `format` of another type, used as an argument inside `configure { }`. */
private class SetupWithFormatter(private val format: DateTimeFormatter, private val out: MutableList<String>) {
    fun setup() = KLogger.configure {
        val year = LocalDate.of(2026, 1, 1).format(format)
        logToCustom { level, _, message -> out.add("$year|$level|$message") }
    }
}
