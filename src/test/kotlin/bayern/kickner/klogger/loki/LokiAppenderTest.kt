package bayern.kickner.klogger.loki

import bayern.kickner.klogger.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LokiAppenderTest {

    private lateinit var server: TestHttpServer
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        server = TestHttpServer()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        LokiAppender.contextFields = emptyMap()
        KLogger.resetForTest()
    }

    /** Registers Loki with a flush loop that never fires during a test, so tests flush manually. */
    private fun configureLoki(
        url: String = server.url,
        flushInterval: Duration = 1.hours,
        extra: bayern.kickner.klogger.LoggerDsl.() -> Unit = {},
    ) = KLogger.configure {
        logToLoki(lokiBaseUrl = url, appName = "test", bearerToken = "", flushInterval = flushInterval, scope = scope)
        extra()
    }

    @Test
    fun `HTTP error response is reported on stderr instead of through KLogger`() {
        server.status = 500
        val dispatched = mutableListOf<String>()
        configureLoki { logToCustom { _, _, message -> dispatched.add(message) } }

        KLogger.info("tag") { "payload" }
        val stderr = captureStderr { LokiAppender.flush() }

        assertEquals(1, server.bodies.size)
        // Reporting through KLogger would feed the failure back into the Loki buffer
        assertEquals(listOf("payload"), dispatched, "appender failure was dispatched through KLogger")
        assertContains(stderr, "500")
    }

    @Test
    fun `unreachable target is reported on stderr instead of through KLogger`() {
        val deadUrl = server.url
        server.close() // port now refuses connections
        val dispatched = mutableListOf<String>()
        configureLoki(url = deadUrl) { logToCustom { _, _, message -> dispatched.add(message) } }

        KLogger.info("tag") { "payload" }
        val stderr = captureStderr { LokiAppender.flush() }

        assertEquals(listOf("payload"), dispatched, "appender failure was dispatched through KLogger")
        assertContains(stderr, "send failed")
    }

    @Test
    fun `flush loop survives a flush that throws`() {
        // batchMaxSize = -1 makes flush() throw (ArrayList(-1)) on every tick. A dead loop would
        // report the exception once; a surviving loop keeps reporting it on every tick.
        withCapturedStderr { stderr ->
            KLogger.configure {
                logToLoki(
                    lokiBaseUrl = server.url, appName = "test", bearerToken = "",
                    flushInterval = 20.milliseconds, batchMaxSize = -1, scope = scope,
                )
            }

            awaitUntil("flush loop to keep running after a failing flush") {
                Regex("Illegal Capacity").findAll(stderr.toString()).count() >= 3
            }
            scope.cancel() // stop the (still failing) loop before stderr is restored
        }
    }

    @Test
    fun `context fields are snapshotted at log time`() {
        configureLoki()
        val context = mutableMapOf("callId" to "first")
        LokiAppender.contextFields = context

        KLogger.info("tag") { "payload" }
        context["callId"] = "second" // mutated in place after the log call
        LokiAppender.flush()

        val body = server.awaitRequests(1).single()
        assertContains(body, "first")
        assertFalse(body.contains("second"), "entry saw a later mutation of the context map: $body")
    }

    @Test
    fun `registering Loki twice does not duplicate log lines`() {
        configureLoki()
        configureLoki() // e.g. reconfiguring later in the app's lifetime

        KLogger.info("tag") { "once" }
        LokiAppender.flush()

        val body = server.awaitRequests(1).single()
        assertEquals(1, Regex("once").findAll(body).count(), "log line was delivered more than once: $body")
    }

    @Test
    fun `JSON payload and Bearer header match Loki specification`() {
        KLogger.configure {
            logToLoki(
                lokiBaseUrl = server.url,
                appName = "test-app",
                bearerToken = "secret-token",
                contextFields = mapOf("traceId" to "12345"),
                flushInterval = 1.hours,
                scope = scope
            )
        }

        KLogger.info("ApiTag") { "User logged in" }
        LokiAppender.flush()

        val body = server.awaitRequests(1).single()
        val headers = server.requestHeaders.single()

        val auth = headers["Authorization"]?.firstOrNull() ?: headers["authorization"]?.firstOrNull()
        assertEquals("Bearer secret-token", auth)

        val json = Json.parseToJsonElement(body).jsonObject
        val streams = json["streams"]!!.jsonArray
        assertEquals(1, streams.size)
        val stream = streams[0].jsonObject
        assertEquals("test-app", stream["stream"]!!.jsonObject["app"]!!.jsonPrimitive.content)

        val values = stream["values"]!!.jsonArray
        assertEquals(1, values.size)
        val entry = values[0].jsonArray
        val timestamp = entry[0].jsonPrimitive.content
        assertTrue(timestamp.toLong() > 0)

        val lineJson = Json.parseToJsonElement(entry[1].jsonPrimitive.content).jsonObject
        assertEquals("INFO", lineJson["level"]!!.jsonPrimitive.content)
        assertEquals("ApiTag", lineJson["tag"]!!.jsonPrimitive.content)
        assertEquals("User logged in", lineJson["message"]!!.jsonPrimitive.content)
        assertEquals("12345", lineJson["traceId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Loki buffer drops oldest entries on overflow`() {
        KLogger.configure {
            logToLoki(
                lokiBaseUrl = server.url,
                appName = "test-app",
                bearerToken = "",
                maxQueueSize = 3,
                flushInterval = 1.hours,
                scope = scope
            )
        }

        repeat(5) { i -> KLogger.info("tag") { "loki-msg$i" } }
        LokiAppender.flush()

        val body = server.awaitRequests(1).single()
        assertFalse(body.contains("loki-msg0"))
        assertFalse(body.contains("loki-msg1"))
        assertTrue(body.contains("loki-msg2"))
        assertTrue(body.contains("loki-msg3"))
        assertTrue(body.contains("loki-msg4"))
    }
}
