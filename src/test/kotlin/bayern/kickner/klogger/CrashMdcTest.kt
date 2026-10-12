package bayern.kickner.klogger

import bayern.kickner.klogger.loki.LokiAppender
import bayern.kickner.klogger.loki.logToLoki
import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

/** The CRASH line of an uncaught exception carries the MDC of the crashing thread, although it is logged elsewhere. */
class CrashMdcTest {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private val previousCalls = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private val logged = CopyOnWriteArrayList<Triple<KLogger.Level, String, String>>()

    @BeforeTest
    fun setUp() {
        MDC.clear()
        KLogger.resetForTest()
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        // Stands in for the JVM's/Android's/Crashlytics' handler that was installed before Klogger's
        Thread.setDefaultUncaughtExceptionHandler { t, e -> previousCalls.add(t.name to e) }
    }

    @AfterTest
    fun tearDown() {
        KloggerUncaughtExceptionHandler.uninstallForTest()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        LokiAppender.contextFields = emptyMap()
        KLogger.resetForTest()
        MDC.clear()
    }

    private fun configure(extra: LoggerDsl.() -> Unit = {}) = KLogger.configure {
        logToCustom { level, tag, message -> logged.add(Triple(level, tag, message)) }
        logUncaughtExceptions()
        extra()
    }

    /** Starts a thread that sets [mdc] and then dies with [error]. */
    private fun crashThread(name: String, error: Throwable, mdc: Map<String, String>) = thread(name = name) {
        mdc.forEach { (k, v) -> MDC.put(k, v) }
        throw error
    }.join()

    @Test
    fun `crash line carries the MDC of the crashing thread`() {
        configure()
        val error = IllegalStateException("boom")

        crashThread("worker-1", error, mapOf("system" to "checkout", "correlationId" to "C1"))

        val (level, tag, message) = logged.single()
        assertEquals(KLogger.Level.CRASH, level)
        assertEquals("UncaughtException", tag)
        assertEquals(
            "Uncaught exception in thread \"worker-1\" {correlationId=C1, system=checkout}\n" + error.stackTraceToString(),
            message,
        )
        assertEquals(listOf("worker-1" to error), previousCalls.map { it.first to it.second })
    }

    @Test
    fun `crash line without MDC is unchanged`() {
        configure()
        val error = IllegalStateException("boom")

        crashThread("worker-2", error, emptyMap())

        assertEquals("Uncaught exception in thread \"worker-2\"\n" + error.stackTraceToString(), logged.single().third)
    }

    @Test
    fun `includeMdc false keeps the MDC out of the crash line`() {
        configure { includeMdc = false }
        val error = IllegalStateException("boom")

        crashThread("worker-3", error, mapOf("correlationId" to "C1"))

        assertEquals("Uncaught exception in thread \"worker-3\"\n" + error.stackTraceToString(), logged.single().third)
    }

    @Test
    fun `Loki gets the MDC of the crashing thread as fields`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            try {
                configure {
                    logToLoki(
                        lokiBaseUrl = server.url,
                        appName = "test",
                        bearerToken = "t",
                        flushInterval = 1.hours,
                        scope = scope
                    )
                }

                crashThread("worker-4", IllegalStateException("boom"), mapOf("correlationId" to "C1"))

                // The crash handler flushes before handing on, so the batch has already been sent
                val body = server.bodies.single()
                assertContains(body, """\"level\":\"CRASH\"""")
                assertContains(body, """\"correlationId\":\"C1\"}""")
                assertContains(
                    body,
                    """\"message\":\"Uncaught exception in thread \\\"worker-4\\\"\\njava.lang.IllegalStateException: boom"""
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `without a helper thread the crash line still carries the MDC`() {
        configure()
        KloggerUncaughtExceptionHandler.startWorker =
            { _, _ -> throw OutOfMemoryError("unable to create native thread") }
        val error = RuntimeException("no threads left")

        crashThread("worker-5", error, mapOf("correlationId" to "C1"))

        assertTrue(logged.single().third.startsWith("Uncaught exception in thread \"worker-5\" {correlationId=C1}\n"))
        assertEquals(listOf<Throwable>(error), previousCalls.map { it.second })
    }

    @Test
    fun `a failing coroutine without exception handler logs the MDC of its MDCContext`() {
        configure()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val error = IllegalStateException("coroutine boom")

        runBlocking {
            scope.launch(MDCContext(mapOf("correlationId" to "C1"))) { throw error }.join()
        }
        scope.cancel()

        val message = logged.single().third
        assertTrue(message.startsWith("Uncaught exception in thread \"DefaultDispatcher-worker-"), message)
        assertContains(message.lineSequence().first(), "{correlationId=C1}")
        assertEquals(listOf<Throwable>(error), previousCalls.map { it.second })
    }
}
