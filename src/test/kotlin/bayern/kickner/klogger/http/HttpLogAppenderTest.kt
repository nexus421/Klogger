package bayern.kickner.klogger.http

import bayern.kickner.klogger.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
    fun `drain against a server that never answers returns within its timeout`() {
        ServerSocket(0).use { blackhole ->
            // Accepts connections but never answers: without the remaining-time based socket timeouts
            // a single request would wait the full 3 s read timeout.
            thread(isDaemon = true) { runCatching { while (true) blackhole.accept() } }
            val appender = appender(url = "http://127.0.0.1:${blackhole.localPort}")
            KLogger.configure { logToHttp(appender) }
            KLogger.info("tag") { "payload" }

            val took = measureTime { captureStderr { appender.drain(500.milliseconds) } }

            assertTrue(took < 2.5.seconds, "drain took $took")
        }
    }

    @Test
    fun `drain reports complete at an empty buffer`() {
        val appender = appender()
        KLogger.configure { logToHttp(appender) }

        assertTrue(appender.drain(1.seconds))

        assertEquals(0, server.bodies.size)
    }

    @Test
    fun `drain reports complete when the last batch is exactly full`() {
        val appender = appender().apply { batchMaxSize = 2 }
        KLogger.configure { logToHttp(appender) }
        repeat(4) { i -> KLogger.info("tag") { "m$i" } }

        assertTrue(appender.drain(3.seconds))

        assertEquals(listOf("m0\nm1", "m2\nm3"), server.bodies.toList())
    }

    @Test
    fun `drain reports incomplete when its time runs out with batches left`() {
        server.responseDelayMs = 120
        val appender = appender().apply { batchMaxSize = 1 }
        KLogger.configure { logToHttp(appender) }
        repeat(10) { i -> KLogger.info("tag") { "m$i" } }

        assertFalse(appender.drain(300.milliseconds))
    }

    @Test
    fun `flush sends nothing once the appender was stopped via its scope`() {
        val appender = appender()
        KLogger.configure { logToHttp(appender) }
        scope.cancel() // the documented way to stop sending

        KLogger.info("tag") { "after stop" }

        assertTrue(KLogger.flush(1.seconds))
        assertEquals(0, server.bodies.size)
    }
}
