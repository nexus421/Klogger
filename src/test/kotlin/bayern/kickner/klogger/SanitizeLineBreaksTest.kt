package bayern.kickner.klogger

import bayern.kickner.klogger.loki.LokiAppender
import bayern.kickner.klogger.loki.logToLoki
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.MDC
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class SanitizeLineBreaksTest {

    private data class Line(val tag: String, val message: String)

    private val received = mutableListOf<Line>()
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        MDC.clear()
        KLogger.resetForTest()
        dir = Files.createTempDirectory("klogger-sanitize").toFile()
    }

    @AfterTest
    fun tearDown() {
        MDC.clear()
        KLogger.resetForTest()
        dir.deleteRecursively()
    }

    private fun LoggerDsl.collect() = logToCustom { _, tag, message -> received.add(Line(tag, message)) }

    private val forged = "login failed\n01.01.2026 00:00:00 INFO/Auth: login ok for admin"

    @Test
    fun `is off by default and leaves line breaks untouched`() {
        KLogger.configure { collect() }

        KLogger.info("t\nag") { "a\r\nb\nc" }

        assertEquals(Line("t\nag", "a\r\nb\nc"), received.single())
    }

    @Test
    fun `escapes line breaks in the message`() {
        KLogger.configure { sanitizeLineBreaks(); collect() }

        KLogger.info("tag") { forged }

        assertEquals("login failed\\n01.01.2026 00:00:00 INFO/Auth: login ok for admin", received.single().message)
    }

    @Test
    fun `escapes carriage returns and CRLF in message and tag`() {
        KLogger.configure { sanitizeLineBreaks(); collect() }

        KLogger.info("t\r\nag") { "a\rb\r\nc" }

        assertEquals(Line("t\\r\\nag", "a\\rb\\r\\nc"), received.single())
    }

    @Test
    fun `text without line breaks stays byte-identical`() {
        KLogger.configure { sanitizeLineBreaks(); collect() }

        KLogger.info("tag") { "plain message with \\ backslash and tab\t" }

        assertEquals(Line("tag", "plain message with \\ backslash and tab\t"), received.single())
    }

    @Test
    fun `a forged log line cannot be written to a file`() {
        val file = File(dir, "app.log")
        KLogger.configure { sanitizeLineBreaks(); logToFile(file) }

        KLogger.info("tag") { forged }

        assertEquals(1, file.readLines().size)
    }

    @Test
    fun `without it the same message does forge a second line in the file`() {
        val file = File(dir, "app.log")
        KLogger.configure { logToFile(file) }

        KLogger.info("tag") { forged }

        assertEquals(2, file.readLines().size)
    }

    @Test
    fun `escapes line breaks in MDC keys and values`() {
        MDC.put("us\ner", "bob\r\nINFO forged")
        KLogger.configure { sanitizeLineBreaks(); collect() }

        KLogger.info("tag") { "msg" }

        assertEquals("msg {us\\ner=bob\\r\\nINFO forged}", received.single().message)
    }

    @Test
    fun `the setting survives a later configure call`() {
        KLogger.configure { sanitizeLineBreaks() }
        KLogger.configure { collect() }

        KLogger.info("tag") { "a\nb" }

        assertEquals("a\\nb", received.single().message)
    }

    @Test
    fun `can be switched off again`() {
        KLogger.configure { sanitizeLineBreaks(); sanitizeLineBreaks(false); collect() }

        KLogger.info("tag") { "a\nb" }

        assertEquals("a\nb", received.single().message)
    }

    @Test
    fun `Loki keeps receiving the raw message, JSON escaping already protects it`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            try {
                KLogger.configure {
                    sanitizeLineBreaks()
                    logToLoki(server.url, "test", "", flushInterval = 1.hours, scope = scope)
                }

                KLogger.info("tag") { "a\nb" }
                LokiAppender.flush()

                val body = Json.parseToJsonElement(server.awaitRequests(1).single()).jsonObject
                val line = body["streams"]!!.jsonArray[0].jsonObject["values"]!!.jsonArray[0].jsonArray[1].jsonPrimitive.content
                assertEquals("a\nb", Json.parseToJsonElement(line).jsonObject["message"]!!.jsonPrimitive.content)
            } finally {
                scope.cancel()
                LokiAppender.contextFields = emptyMap()
            }
        }
    }
}
