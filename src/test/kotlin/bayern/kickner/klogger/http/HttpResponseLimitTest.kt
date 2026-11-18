package bayern.kickner.klogger.http

import bayern.kickner.klogger.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class HttpResponseLimitTest {

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        KLogger.resetForTest()
    }

    private fun registered(url: String): HttpLogAppender {
        val appender = HttpLogAppender(
            url = url,
            bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
            initialFlushInterval = 1.hours,
            initialScope = scope,
        )
        KLogger.configure { logToHttp(appender) }
        return appender
    }

    @Test
    fun `a huge response body is not read to the end`() {
        val written = AtomicLong()
        val finished = AtomicBoolean()
        ScriptedHttpServer { exchange ->
            exchange.sendResponseHeaders(200, 0) // chunked
            val chunk = ByteArray(1024 * 1024)
            try {
                repeat(64) {
                    exchange.responseBody.write(chunk)
                    exchange.responseBody.flush()
                    written.addAndGet(chunk.size.toLong())
                }
            } catch (_: IOException) {
                // the client hung up
            }
            exchange.close()
            finished.set(true)
        }.use { server ->
            val appender = registered(server.url)

            KLogger.info("tag") { "payload" }
            captureStderr { appender.flush() }
            awaitUntil("server handler finished") { finished.get() }

            assertTrue(written.get() < 16L * 1024 * 1024, "server could write ${written.get()} of 64 MiB: client read it all")
        }
    }

    @Test
    fun `a slowly trickling response does not hold a drain beyond its deadline`() {
        ScriptedHttpServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                repeat(80) {
                    exchange.responseBody.write(1)
                    exchange.responseBody.flush()
                    Thread.sleep(100)
                }
            } catch (_: IOException) {
            }
            exchange.close()
        }.use { server ->
            val appender = registered(server.url)

            KLogger.info("tag") { "payload" }
            val took = measureTime { appender.drain(1.seconds) }

            assertTrue(took < 4.seconds, "drain(1 s) took $took against a response that trickles for 8 s")
        }
    }

    @Test
    fun `a small response body is still consumed and later batches are delivered`() {
        ScriptedHttpServer { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }.use { server ->
            val appender = registered(server.url)

            KLogger.info("tag") { "one" }
            appender.flush()
            KLogger.info("tag") { "two" }
            appender.flush()

            assertEquals(2, server.requests.get())
        }
    }

    @Test
    fun `an error response with a body is reported with its status`() {
        ScriptedHttpServer { exchange ->
            val body = "<html>bad gateway</html>".toByteArray()
            exchange.sendResponseHeaders(502, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }.use { server ->
            val appender = registered(server.url)

            KLogger.info("tag") { "payload" }
            val stderr = captureStderr { appender.flush() }

            assertContains(stderr, "502")
        }
    }
}
