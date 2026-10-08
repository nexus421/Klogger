package bayern.kickner.klogger

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ShutdownHookTest {

    private lateinit var server: TestHttpServer

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        server = TestHttpServer()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        KLogger.resetForTest()
    }

    /** Runs [ShutdownHookMain] in a fresh JVM and returns its exit code. */
    private fun runChildJvm(mode: String): Int {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            ShutdownHookMain::class.java.name, server.url, mode,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child JVM did not exit: $output")
        assertEquals(0, process.exitValue(), "child JVM failed: $output")
        return process.exitValue()
    }

    @Test
    fun `shutdown hook flushes buffered entries when the JVM exits`() {
        runChildJvm("hook")

        assertEquals(listOf("bye"), server.bodies.toList())
    }

    @Test
    fun `without flushOnShutdown buffered entries are lost on exit`() {
        runChildJvm("nohook")

        assertEquals(emptyList(), server.bodies.toList())
    }

    @Test
    fun `switching flushOnShutdown off again removes the hook`() {
        runChildJvm("hook-then-off")

        assertEquals(emptyList(), server.bodies.toList())
    }

    @Test
    fun `no hook is registered by default`() {
        KLogger.configure { logToConsole() }

        assertNull(KLogger.shutdownHookForTest)
    }

    @Test
    fun `repeated configure keeps exactly one hook and null removes it`() {
        KLogger.configure { flushOnShutdown = 1.seconds }
        val hook = assertNotNull(KLogger.shutdownHookForTest)
        KLogger.configure { flushOnShutdown = 2.seconds }
        KLogger.configure { }
        assertSame(hook, KLogger.shutdownHookForTest)
        assertEquals(2.seconds, KLoggerDslProbe.flushOnShutdown())

        KLogger.configure { flushOnShutdown = null }

        assertNull(KLogger.shutdownHookForTest)
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "hook was still registered")
    }

    @Test
    fun `resetForTest removes the hook`() {
        KLogger.configure { flushOnShutdown = 1.seconds }
        val hook = assertNotNull(KLogger.shutdownHookForTest)

        KLogger.resetForTest()

        assertNull(KLogger.shutdownHookForTest)
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "hook was still registered")
    }

    @Test
    fun `flushOnShutdown rejects non-positive durations`() {
        assertFailsWith<IllegalArgumentException> { KLogger.configure { flushOnShutdown = Duration.ZERO } }
        assertFailsWith<IllegalArgumentException> { KLogger.configure { flushOnShutdown = (-1).seconds } }
        assertNull(KLogger.shutdownHookForTest)
    }

    /** Reads the current setting back through the DSL getter. */
    private object KLoggerDslProbe {
        fun flushOnShutdown(): Duration? {
            var value: Duration? = null
            KLogger.configure { value = flushOnShutdown }
            return value
        }
    }
}
