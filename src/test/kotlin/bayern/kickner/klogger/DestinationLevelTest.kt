package bayern.kickner.klogger

import bayern.kickner.klogger.http.HttpLogAppender
import bayern.kickner.klogger.http.logToHttp
import bayern.kickner.klogger.loki.LokiAppender
import bayern.kickner.klogger.loki.logToLoki
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class DestinationLevelTest {

    private lateinit var server: TestHttpServer
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        server = TestHttpServer()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dir = Files.createTempDirectory("klogger-level").toFile()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
        KLogger.resetForTest()
    }

    private fun logEveryLevel() {
        KLogger.debug("t") { "d" }
        KLogger.info("t") { "i" }
        KLogger.warn("t") { "w" }
        KLogger.error("t") { "e" }
        KLogger.crash("t") { "c" }
    }

    private fun appender() = HttpLogAppender(
        url = server.url,
        bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
        initialFlushInterval = 1.hours,
        initialScope = scope,
    )

    @Test
    fun `custom destination minLevel filters and other destinations are unaffected`() {
        val all = mutableListOf<String>()
        val warnAndUp = mutableListOf<String>()
        KLogger.configure {
            logToCustom { _, _, message -> all.add(message) }
            logToCustom(KLogger.Level.WARN) { _, _, message -> warnAndUp.add(message) }
        }

        logEveryLevel()

        assertEquals(listOf("d", "i", "w", "e", "c"), all)
        assertEquals(listOf("w", "e", "c"), warnAndUp)
    }

    @Test
    fun `destination minLevel also applies in debug mode`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            minLevel = KLogger.Level.CRASH
            debug = true
            logToCustom(minLevel = KLogger.Level.ERROR) { _, _, message -> received.add(message) }
        }

        logEveryLevel()

        assertEquals(listOf("e", "c"), received)
    }

    @Test
    fun `global minLevel still filters first and keeps messages lazy`() {
        val received = mutableListOf<String>()
        var evaluated = false
        KLogger.configure {
            minLevel = KLogger.Level.ERROR
            logToCustom(KLogger.Level.DEBUG) { _, _, message -> received.add(message) }
        }

        KLogger.info("t") { evaluated = true; "i" }
        KLogger.error("t") { "e" }

        assertFalse(evaluated)
        assertEquals(listOf("e"), received)
    }

    @Test
    fun `logToCustom accepts a function value together with a level`() {
        val received = mutableListOf<String>()
        val sink: (KLogger.Level, String, String) -> Unit = { _, _, message -> received.add(message) }
        KLogger.configure { logToCustom(KLogger.Level.ERROR, sink) }

        logEveryLevel()

        assertEquals(listOf("e", "c"), received)
    }

    @Test
    fun `file minLevel`() {
        val file = File(dir, "app.log")
        KLogger.configure { logToFile(file, minLevel = KLogger.Level.ERROR) }

        logEveryLevel()

        assertEquals(
            listOf("ERROR/t: e", "CRASH/t: c"),
            file.readLines().map { it.substringAfter(' ').substringAfter(' ') })
    }

    @Test
    fun `console minLevel`() {
        KLogger.configure { logToConsole(minLevel = KLogger.Level.WARN) }
        lateinit var stderr: String

        val stdout = captureStdout { stderr = captureStderr { logEveryLevel() } }

        assertEquals(listOf("WARN/t: w"), stdout.trim().lines().map { it.substringAfter(' ').substringAfter(' ') })
        assertEquals(
            listOf("ERROR/t: e", "CRASH/t: c"),
            stderr.trim().lines().map { it.substringAfter(' ').substringAfter(' ') })
    }

    @Test
    fun `cached forwarding minLevel`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            logToCachedForwarding(
                File(dir, "cache").path,
                target = { _, _, message -> received.add(message) },
                minLevel = KLogger.Level.ERROR,
            )
        }

        logEveryLevel()

        assertEquals(listOf("e", "c"), received)
    }

    @Test
    fun `HTTP minLevel is kept when the appender is registered twice`() {
        val appender = appender()
        KLogger.configure { logToHttp(appender, minLevel = KLogger.Level.ERROR) }
        KLogger.configure { logToHttp(appender, minLevel = KLogger.Level.ERROR) }

        logEveryLevel()
        appender.flush()

        assertEquals(listOf("e\nc"), server.bodies.toList())
    }

    @Test
    fun `registering the HTTP appender again without minLevel removes the filter`() {
        val appender = appender()
        KLogger.configure { logToHttp(appender, minLevel = KLogger.Level.ERROR) }
        KLogger.configure { logToHttp(appender) }

        logEveryLevel()
        appender.flush()

        assertEquals(listOf("d\ni\nw\ne\nc"), server.bodies.toList())
    }

    @Test
    fun `Loki minLevel`() {
        KLogger.configure {
            logToLoki(server.url, "test", "", flushInterval = 1.hours, scope = scope, minLevel = KLogger.Level.WARN)
        }

        logEveryLevel()
        LokiAppender.flush()

        val body = server.bodies.single()
        // Each Loki line is a JSON string inside the push body, so its quotes arrive escaped: message\":\"w\"
        val messages = Regex("""message\\":\\"(\w)\\"""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("w", "e", "c"), messages)
    }

    @Test
    fun `level filter passes flush and its result through to the wrapped destination`() {
        var flushedWith: Duration? = null
        val filter = LevelFilterDestination(KLogger.Level.WARN, object : Destination {
            override fun log(level: KLogger.Level, tag: String, message: String) = Unit
            override fun flush(timeout: Duration): Boolean {
                flushedWith = timeout
                return false
            }
        })

        assertFalse(filter.flush(2.seconds), "the wrapped destination's result must be passed through")
        assertEquals(2.seconds, flushedWith)
    }
}
