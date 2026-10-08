package bayern.kickner.klogger

import bayern.kickner.klogger.http.HttpLogAppender
import bayern.kickner.klogger.http.logToHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class FlushTest {

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

    /** Appender whose own flush loop never fires during a test. */
    private fun appender() = HttpLogAppender(
        url = server.url,
        bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
        initialFlushInterval = 1.hours,
        initialBatchMaxSize = 50,
        initialScope = scope,
    )

    private fun receivedMessages() = server.bodies.flatMap { it.split("\n") }

    @Test
    fun `flush drains all batches in order`() {
        KLogger.configure { logToHttp(appender()) }
        repeat(120) { i -> KLogger.info("t") { "m$i" } }

        assertTrue(KLogger.flush(3.seconds))

        assertEquals(3, server.bodies.size)
        assertEquals((0 until 120).map { "m$it" }, receivedMessages())
    }

    @Test
    fun `flush returns false within the timeout when a destination blocks`() {
        KLogger.configure {
            logTo(object : Destination {
                override fun log(level: KLogger.Level, tag: String, message: String) = Unit
                override fun flush(timeout: Duration): Boolean {
                    Thread.sleep(10_000)
                    return true
                }
            })
        }

        val took = measureTime { assertFalse(KLogger.flush(200.milliseconds)) }

        assertTrue(took < 2.seconds, "flush took $took")
    }

    @Test
    fun `flush waits for a batch the flush loop has in flight`() {
        server.responseDelayMs = 500
        val appender = appender()
        KLogger.configure { logToHttp(appender) }
        repeat(10) { i -> KLogger.info("t") { "m$i" } }
        val loop = thread { appender.flush() } // takes the only batch, the server answers after 500 ms
        server.awaitRequests(1)

        assertTrue(KLogger.flush(3.seconds))

        // Without waiting for the in-flight batch, flush would find an empty buffer and return immediately
        assertEquals(1, server.completed.get())
        loop.join()
    }

    @Test
    fun `flush returns false while the flush loop keeps sending past the timeout`() {
        server.responseDelayMs = 1_500
        val appender = appender()
        KLogger.configure { logToHttp(appender) }
        KLogger.info("t") { "a" }
        val loop = thread { appender.flush() } // holds the send lock for 1.5 s
        server.awaitRequests(1)
        KLogger.info("t") { "b" }

        // "b" is still buffered when the time runs out
        assertFalse(KLogger.flush(300.milliseconds))
        loop.join()
    }

    @Test
    fun `flush returns false when the time runs out with batches left`() {
        server.responseDelayMs = 120
        val appender = appender().apply { batchMaxSize = 1 }
        KLogger.configure { logToHttp(appender) }
        repeat(10) { i -> KLogger.info("t") { "m$i" } }

        assertFalse(KLogger.flush(300.milliseconds))
    }

    @Test
    fun `concurrent loop flush and drain deliver every entry exactly once and in order`() {
        val appender = appender()
        KLogger.configure { logToHttp(appender) }
        repeat(500) { i -> KLogger.info("t") { "m$i" } }

        val loop = thread { repeat(5) { appender.flush() } }
        assertTrue(KLogger.flush(5.seconds))
        loop.join()

        assertEquals((0 until 500).map { "m$it" }, receivedMessages())
    }

    @Test
    fun `a throwing destination flush does not stop the others`() {
        KLogger.configure {
            logTo(object : Destination {
                override fun log(level: KLogger.Level, tag: String, message: String) = Unit
                override fun flush(timeout: Duration): Boolean = throw IllegalStateException("broken")
            })
            logToHttp(appender())
        }
        KLogger.info("t") { "after broken" }

        // Incomplete, because the broken destination could not confirm its flush - but the others still ran
        assertFalse(KLogger.flush(3.seconds))

        assertEquals(listOf("after broken"), receivedMessages())
    }

    @Test
    fun `flush without buffering destinations returns true`() {
        assertTrue(KLogger.flush())
        KLogger.configure {
            logToConsole()
            logToCustom { _, _, _ -> }
        }
        assertTrue(KLogger.flush())
    }

    @Test
    fun `flush on an interrupted thread returns false instead of throwing and keeps the interrupt status`() {
        KLogger.configure { logToHttp(appender()) }
        KLogger.info("t") { "x" }
        Thread.currentThread().interrupt()
        try {
            assertFalse(KLogger.flush(1.seconds))
            assertTrue(Thread.currentThread().isInterrupted, "interrupt status was swallowed")
        } finally {
            Thread.interrupted() // don't leak the flag into other tests
        }
    }

    @Test
    fun `flush rejects a non-positive timeout`() {
        assertFailsWith<IllegalArgumentException> { KLogger.flush(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { KLogger.flush((-1).seconds) }
    }
}
