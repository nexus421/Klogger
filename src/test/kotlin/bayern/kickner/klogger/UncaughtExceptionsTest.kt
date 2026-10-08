package bayern.kickner.klogger

import bayern.kickner.klogger.http.HttpLogAppender
import bayern.kickner.klogger.http.logToHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class UncaughtExceptionsTest {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private val previousCalls = CopyOnWriteArrayList<Pair<String, Throwable>>()

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        // Stands in for the JVM's/Android's/Crashlytics' handler that was installed before Klogger's
        Thread.setDefaultUncaughtExceptionHandler { t, e -> previousCalls.add(t.name to e) }
    }

    @AfterTest
    fun tearDown() {
        KloggerUncaughtExceptionHandler.uninstallForTest()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        KLogger.resetForTest()
    }

    private fun crashThread(name: String, error: Throwable) = thread(name = name) { throw error }.join()

    @Test
    fun `uncaught exception is logged as CRASH and handed to the previous handler`() {
        val logged = CopyOnWriteArrayList<Triple<KLogger.Level, String, String>>()
        KLogger.configure {
            logToCustom { level, tag, message -> logged.add(Triple(level, tag, message)) }
            logUncaughtExceptions()
        }
        val error = IllegalStateException("boom")

        crashThread("worker-1", error)

        assertEquals(1, logged.size)
        val (level, tag, message) = logged.single()
        assertEquals(KLogger.Level.CRASH, level)
        assertEquals("UncaughtException", tag)
        assertEquals("Uncaught exception in thread \"worker-1\"\n" + error.stackTraceToString(), message)
        assertEquals(listOf("worker-1" to error), previousCalls.map { it.first to it.second })
    }

    @Test
    fun `calling logUncaughtExceptions twice does not log twice`() {
        val logged = CopyOnWriteArrayList<String>()
        KLogger.configure {
            logToCustom { _, _, message -> logged.add(message) }
            logUncaughtExceptions()
        }
        KLogger.configure { logUncaughtExceptions() }

        crashThread("worker-2", RuntimeException("once"))

        assertEquals(1, logged.size)
        assertEquals(1, previousCalls.size)
    }

    @Test
    fun `without a previous handler the JVM default output is printed`() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        KLogger.configure {
            logToCustom { _, _, _ -> }
            logUncaughtExceptions()
        }

        val stderr = captureStderr { crashThread("w2", RuntimeException("kaputt")) }

        assertEquals(
            1,
            Regex("""Exception in thread "w2" java\.lang\.RuntimeException: kaputt""").findAll(stderr).count(),
            stderr
        )
        assertContains(stderr, "\tat ")
    }

    @Test
    fun `the crash is flushed to buffering destinations before the thread dies`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            val appender = HttpLogAppender(
                url = server.url,
                bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
                initialFlushInterval = 1.hours, // only the crash handler's flush can deliver it
                initialScope = scope,
            )
            KLogger.configure {
                logToHttp(appender)
                logUncaughtExceptions()
            }

            crashThread("worker-3", RuntimeException("flushed"))

            assertEquals(1, server.bodies.size)
            assertContains(server.bodies.single(), "Uncaught exception in thread \"worker-3\"")
        }
        scope.cancel()
    }

    @Test
    fun `a crash that cannot even be logged is still flushed and handed over`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            val appender = HttpLogAppender(
                url = server.url,
                bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
                initialFlushInterval = 1.hours,
                initialScope = scope,
            )
            KLogger.configure {
                logToHttp(appender)
                logUncaughtExceptions()
            }
            KLogger.info("t") { "logged before the crash" }
            // Building the CRASH message calls stackTraceToString(), which throws for this exception
            val unprintable = object : RuntimeException("unprintable") {
                override fun printStackTrace(s: PrintWriter) = throw IllegalStateException("cannot print")
            }

            crashThread("worker-4", unprintable)

            assertEquals(listOf("logged before the crash"), server.bodies.toList(), "flush was skipped")
            assertEquals(listOf<Throwable>(unprintable), previousCalls.map { it.second })
        }
        scope.cancel()
    }

    @Test
    fun `a crash on an interrupted thread is still flushed and the interrupt status is handed on`() {
        val interruptedInPrevious = CopyOnWriteArrayList<Boolean>()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> interruptedInPrevious.add(Thread.currentThread().isInterrupted) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            val appender = HttpLogAppender(
                url = server.url,
                bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
                initialFlushInterval = 1.hours,
                initialScope = scope,
            )
            KLogger.configure {
                logToHttp(appender)
                logUncaughtExceptions()
            }

            thread(name = "worker-5") {
                Thread.currentThread().interrupt() // e.g. catch (e: InterruptedException) { interrupt(); throw ... }
                throw RuntimeException("interrupted crash")
            }.join()

            assertEquals(1, server.bodies.size, "crash was not flushed")
            assertContains(server.bodies.single(), "interrupted crash")
            assertEquals(listOf(true), interruptedInPrevious.toList())
        }
        scope.cancel()
    }

    @Test
    fun `a hanging destination cannot keep the crash from the previous handler`() {
        KLogger.configure {
            logToCustom { level, _, _ -> if (level == KLogger.Level.CRASH) Thread.sleep(10_000) }
            logUncaughtExceptions(flushTimeout = 300.milliseconds)
        }
        val error = RuntimeException("hang")

        val took = measureTime { crashThread("worker-6", error) }

        assertEquals(listOf<Throwable>(error), previousCalls.map { it.second })
        assertTrue(took < 3.seconds, "crash handling took $took")
    }

    @Test
    fun `without a helper thread the crash is still logged on the crashing thread`() {
        val logged = CopyOnWriteArrayList<KLogger.Level>()
        KLogger.configure {
            logToCustom { level, _, _ -> logged.add(level) }
            logUncaughtExceptions()
        }
        KloggerUncaughtExceptionHandler.startWorker =
            { _, _ -> throw OutOfMemoryError("unable to create native thread") }
        val error = RuntimeException("no threads left")

        crashThread("worker-7", error)

        assertEquals(listOf(KLogger.Level.CRASH), logged.toList())
        assertEquals(listOf<Throwable>(error), previousCalls.map { it.second })
    }

    @Test
    fun `a crash caused by crash logging itself is handed on but not logged again`() {
        val crashLines = CopyOnWriteArrayList<String>()
        KLogger.configure {
            logToCustom { level, _, message -> if (level == KLogger.Level.CRASH) crashLines.add(message) }
            // A broken sink that sends on a fire-and-forget thread which dies
            logToCustom { _, _, _ -> thread(name = "sink-sender") { throw RuntimeException("sink down") } }
            logUncaughtExceptions()
        }

        KLogger.info("t") { "one line" }
        awaitUntil("both sink threads to reach the previous handler") { previousCalls.size >= 2 }
        Thread.sleep(300) // a loop would keep producing CRASH lines

        assertEquals(1, crashLines.size, "crash logging looped: ${crashLines.size} CRASH lines")
        assertEquals(2, previousCalls.size)
    }

    @Test
    fun `an interrupt that arrives while waiting for the crash log is kept`() {
        KLogger.configure {
            logToCustom { level, _, _ -> if (level == KLogger.Level.CRASH) Thread.sleep(2_000) }
            logUncaughtExceptions(flushTimeout = 1.seconds)
        }
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        var interruptedAfter = false
        // Libraries such as kotlinx-coroutines call the handler directly on threads that keep running
        val caller = thread(name = "caller") {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("reported"))
            interruptedAfter = Thread.currentThread().isInterrupted
        }
        Thread.sleep(200)
        caller.interrupt()
        caller.join()

        assertTrue(interruptedAfter, "interrupt during the wait was swallowed")
    }

    @Test
    fun `no handler is installed unless requested`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()

        KLogger.configure { logToConsole() }

        assertSame(before, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `logUncaughtExceptions rejects a non-positive flush timeout`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()

        assertFailsWith<IllegalArgumentException> { KLogger.configure { logUncaughtExceptions(Duration.ZERO) } }

        assertSame(before, Thread.getDefaultUncaughtExceptionHandler())
    }
}
