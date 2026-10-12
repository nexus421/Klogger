package bayern.kickner.klogger

import bayern.kickner.klogger.loki.LokiAppender
import bayern.kickner.klogger.loki.logToLoki
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

/**
 * Runs only in the `testWithoutSlf4j` task, where slf4j-api and kotlinx-coroutines-slf4j are removed from the
 * classpath. Must not reference any SLF4J type.
 */
class MdcWithoutSlf4jTest {

    @BeforeTest
    fun setUp() = KLogger.resetForTest()

    @AfterTest
    fun tearDown() = KLogger.resetForTest()

    @Test
    fun `slf4j is really absent`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.slf4j.MDC") }
        assertFailsWith<ClassNotFoundException> { Class.forName("kotlinx.coroutines.slf4j.MDCContext") }
    }

    @Test
    fun `logging without slf4j adds no context and does not throw`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            includeMdc = true
            logToCustom { _, _, message -> received += message }
        }

        KLogger.info("T") { "ohne slf4j" }

        assertEquals(listOf("ohne slf4j"), received)
        assertEquals(emptyMap(), MdcSupport.snapshot())
    }

    @Test
    fun `crash logging works without slf4j`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val handedOn = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn += e }
        try {
            val received = java.util.concurrent.CopyOnWriteArrayList<String>()
            KLogger.configure {
                logToCustom { _, _, message -> received += message }
                logUncaughtExceptions()
            }
            val error = IllegalStateException("boom")

            kotlin.concurrent.thread(name = "worker-x") { throw error }.join()

            assertEquals("Uncaught exception in thread \"worker-x\"\n" + error.stackTraceToString(), received.single())
            assertEquals(listOf<Throwable>(error), handedOn)
        } finally {
            KloggerUncaughtExceptionHandler.uninstallForTest()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `Loki gets no MDC fields without slf4j`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            try {
                KLogger.configure {
                    logToLoki(
                        lokiBaseUrl = server.url,
                        appName = "test",
                        bearerToken = "t",
                        flushInterval = 1.hours,
                        scope = scope
                    )
                }

                KLogger.info("T") { "ohne slf4j" }
                assertTrue(KLogger.flush())

                assertContains(
                    server.bodies.single(),
                    """{\"level\":\"INFO\",\"tag\":\"T\",\"message\":\"ohne slf4j\"}"""
                )
            } finally {
                scope.cancel()
                LokiAppender.contextFields = emptyMap()
            }
        }
    }
}
