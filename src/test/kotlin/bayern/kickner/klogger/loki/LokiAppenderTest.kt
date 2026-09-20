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
}
