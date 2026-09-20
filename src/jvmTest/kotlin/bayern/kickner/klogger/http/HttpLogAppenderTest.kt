package bayern.kickner.klogger.http

import bayern.kickner.klogger.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class HttpLogAppenderTest {

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
        KLogger.resetForTest()
    }

    /** Appender whose flush loop never fires during a test, so tests flush manually. */
    private fun appender(
        url: String = server.url,
        bodyBuilder: (List<LogEntry>) -> String = { batch -> batch.joinToString("\n") { it.message } },
    ) = HttpLogAppender(
        url = url,
        bodyBuilder = bodyBuilder,
        initialFlushInterval = 1.hours,
        initialScope = scope,
    )

    @Test
    fun `HTTP error response is reported on stderr instead of through KLogger`() {
        server.status = 500
        val appender = appender()
        val dispatched = mutableListOf<String>()
        KLogger.configure {
            logToHttp(appender)
            logToCustom { _, _, message -> dispatched.add(message) }
        }

        KLogger.info("tag") { "payload" }
        val stderr = captureStderr { appender.flush() }

        assertEquals(1, server.bodies.size)
        // Reporting through KLogger would feed the failure back into this very appender
        assertEquals(listOf("payload"), dispatched, "appender failure was dispatched through KLogger")
        assertContains(stderr, "500")
    }

    @Test
    fun `unreachable target is reported on stderr instead of through KLogger`() {
        val deadUrl = server.url
        server.close() // port now refuses connections
        val appender = appender(url = deadUrl)
        val dispatched = mutableListOf<String>()
        KLogger.configure {
            logToHttp(appender)
            logToCustom { _, _, message -> dispatched.add(message) }
        }

        KLogger.info("tag") { "payload" }
        val stderr = captureStderr { appender.flush() }

        assertEquals(listOf("payload"), dispatched, "appender failure was dispatched through KLogger")
        assertContains(stderr, "send failed")
    }

    @Test
    fun `bodyBuilder failure is reported on stderr instead of through KLogger`() {
        val appender = appender(bodyBuilder = { throw IllegalStateException("boom") })
        val dispatched = mutableListOf<String>()
        KLogger.configure {
            logToHttp(appender)
            logToCustom { _, _, message -> dispatched.add(message) }
        }

        KLogger.info("tag") { "payload" }
        val stderr = captureStderr { appender.flush() }

        assertEquals(0, server.bodies.size)
        assertEquals(listOf("payload"), dispatched, "appender failure was dispatched through KLogger")
        assertContains(stderr, "boom")
    }

    @Test
    fun `flush loop survives a flush that throws`() {
        val appender = HttpLogAppender(
            url = server.url,
            bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
            initialFlushInterval = 20.milliseconds,
            initialScope = scope,
        )
        appender.batchMaxSize = -1 // makes flush() throw (ArrayList(-1)) on every tick
        KLogger.configure { logToHttp(appender) }

        // Wait until the loop has actually hit the throwing flush at least once
        withCapturedStderr { stderr ->
            awaitUntil("flush loop to hit the failing flush") { stderr.toString().contains("Illegal Capacity") }
        }

        // A single bad flush must not kill the loop for the rest of the JVM's lifetime
        appender.batchMaxSize = 50
        KLogger.info("tag") { "after recovery" }
        assertEquals("after recovery", server.awaitRequests(1).single())
    }

    @Test
    fun `registering the same appender twice does not duplicate log lines`() {
        val appender = appender()
        KLogger.configure { logToHttp(appender) }
        KLogger.configure { logToHttp(appender) } // e.g. reconfiguring later in the app's lifetime

        KLogger.info("tag") { "once" }
        appender.flush()

        assertEquals(listOf("once"), server.awaitRequests(1))
    }

    @Test
    fun `start only launches the flush loop once`() {
        val appender = appender()

        assertTrue(appender.start(), "first start should launch the loop")
        assertFalse(appender.start(), "second start must not launch a second loop")
    }

    @Test
    fun `headers including Bearer and BasicAuth and Content-Type are sent correctly`() {
        val appender = appender().apply {
            addBearer("my-secret-token")
            addContentTypeApplicationJson()
            addHeader("X-Custom-Header", "custom-value")
        }
        KLogger.configure { logToHttp(appender) }

        KLogger.info("tag") { "with-headers" }
        appender.flush()

        server.awaitRequests(1)
        val headers = server.requestHeaders.single()

        val auth = headers["Authorization"]?.firstOrNull() ?: headers["authorization"]?.firstOrNull()
        val contentType = headers["Content-type"]?.firstOrNull() ?: headers["Content-Type"]?.firstOrNull()
        val custom = headers["X-custom-header"]?.firstOrNull() ?: headers["X-Custom-Header"]?.firstOrNull()

        assertEquals("Bearer my-secret-token", auth)
        assertEquals("application/json", contentType)
        assertEquals("custom-value", custom)
    }

    @Test
    fun `basic auth header is correctly base64 encoded`() {
        val appender = appender().apply {
            addBasicAuth("user", "pass")
        }
        KLogger.configure { logToHttp(appender) }

        KLogger.info("tag") { "basic-auth-test" }
        appender.flush()

        server.awaitRequests(1)
        val headers = server.requestHeaders.single()
        val auth = headers["Authorization"]?.firstOrNull() ?: headers["authorization"]?.firstOrNull()

        // "user:pass" in base64 is "dXNlcjpwYXNz"
        assertEquals("Basic dXNlcjpwYXNz", auth)
    }

    @Test
    fun `buffer drops oldest entries on overflow`() {
        val customScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val smallAppender = HttpLogAppender(
            url = server.url,
            bodyBuilder = { batch -> batch.joinToString(",") { it.message } },
            maxQueueSize = 3,
            initialFlushInterval = 1.hours,
            initialScope = customScope,
        )
        KLogger.configure { logToHttp(smallAppender) }

        // Send 5 entries
        repeat(5) { i -> KLogger.info("tag") { "msg$i" } }

        smallAppender.flush()
        customScope.cancel()

        val body = server.awaitRequests(1).single()
        assertEquals("msg2,msg3,msg4", body)
    }
}
