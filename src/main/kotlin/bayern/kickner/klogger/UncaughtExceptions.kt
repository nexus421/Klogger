package bayern.kickner.klogger

import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Default uncaught exception handler installed by [LoggerDsl.logUncaughtExceptions].
 *
 * Logs the exception as [KLogger.Level.CRASH] and flushes buffering destinations via [KLogger.flush], both together
 * bounded by [flushTimeout], and then always hands the exception to [previous], the handler that was installed before.
 * That keeps the platform's crash behaviour intact: the JVM's "Exception in thread ..." output, Android's crash dialog
 * and process kill, or crash reporters such as Crashlytics.
 *
 * Crashes of threads that were started while a crash was being logged (typically by a destination) are handed on but
 * not logged, to rule out an endless crash-log loop. Such a thread keeps that status for its whole life.
 */
internal class KloggerUncaughtExceptionHandler private constructor(
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    /** Upper bound for logging and flushing a crash. Updated by repeated [install] calls. */
    @Volatile
    private var flushTimeout: Duration = Duration.ZERO

    override fun uncaughtException(t: Thread, e: Throwable) {
        // A dying thread may still be interrupted (catch InterruptedException, interrupt(), rethrow): join()
        // below would then return at once and skip the flush. Cleared here, restored before delegating.
        val wasInterrupted = Thread.interrupted()
        var interruptedWhileWaiting = false
        try {
            // Threads started while a crash is being logged (e.g. by a destination that sends on its own thread)
            // inherit this marker. Logging their crash could start the next such thread - an endless loop - so
            // it is only handed on.
            if (!insideCrashLogging.get()) interruptedWhileWaiting = logAndFlush(t, e)
        } finally {
            if (wasInterrupted || interruptedWhileWaiting) Thread.currentThread().interrupt()
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                // Same output as the JVM without any handler. Not delegating to t.threadGroup: it looks up
                // the default handler again - which is this one - and would recurse forever.
                System.err.print("Exception in thread \"${t.name}\" ")
                e.printStackTrace(System.err)
            }
        }
    }

    /**
     * Logs the crash and flushes on a daemon worker while this thread waits at most [flushTimeout]. A destination
     * that hangs (e.g. a custom sink waiting on a dead backend) can delay the crash, but never keep it from
     * reaching the previous handler. The worker also keeps network I/O off a crashing Android main thread.
     *
     * @return `true` if this thread was interrupted while waiting (the caller restores the interrupt status).
     */
    private fun logAndFlush(t: Thread, e: Throwable): Boolean {
        val timeout = flushTimeout
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val worker = runCatching {
            startWorker("klogger-crash") {
                logCrash(t, e)
                val remaining = -deadline.elapsedNow()
                if (remaining.isPositive()) runCatching { KLogger.flush(remaining) }
            }
        }.getOrElse {
            // No thread available (e.g. OutOfMemoryError: unable to create native thread, a common crash): log on
            // this thread instead, without flush, which needs a thread too. A hanging destination could block here,
            // but losing the log line of exactly this crash would be worse.
            logCrash(t, e)
            return false
        }
        return try {
            worker.join(timeout.inWholeMilliseconds.coerceAtLeast(1)) // join(0) would wait forever
            false
        } catch (_: InterruptedException) {
            true
        }
    }

    /** Logs the crash as CRASH. Never throws: logging may fail (e.g. OutOfMemoryError), the flush must still run. */
    private fun logCrash(t: Thread, e: Throwable) {
        val outerMarker = insideCrashLogging.get()
        insideCrashLogging.set(true)
        try {
            runCatching {
                KLogger.crash(TAG) { "Uncaught exception in thread \"${t.name}\"\n${e.stackTraceToString()}" }
            }
        } finally {
            insideCrashLogging.set(outerMarker)
        }
    }

    companion object {
        private const val TAG = "UncaughtException"

        @Volatile
        private var installed: KloggerUncaughtExceptionHandler? = null

        /** Set while a crash is logged; inherited by threads started meanwhile. See [uncaughtException]. */
        private val insideCrashLogging = object : InheritableThreadLocal<Boolean>() {
            override fun initialValue() = false
        }

        private val DEFAULT_START_WORKER: (String, () -> Unit) -> Thread =
            { name, block -> thread(isDaemon = true, name = name, block = block) }

        /** Starts the daemon worker for logging a crash. Replaceable so tests can simulate thread exhaustion. */
        @Volatile
        internal var startWorker: (name: String, block: () -> Unit) -> Thread = DEFAULT_START_WORKER

        /**
         * Installs the handler as the JVM-wide default handler, wrapping the current one. Only the first call
         * installs; later calls just update [flushTimeout]. Wrapping again would log every crash twice if another
         * library (e.g. Crashlytics) has wrapped this handler in the meantime.
         */
        @Synchronized
        fun install(flushTimeout: Duration) {
            installed?.let {
                it.flushTimeout = flushTimeout
                return
            }
            val handler = KloggerUncaughtExceptionHandler(Thread.getDefaultUncaughtExceptionHandler())
            handler.flushTimeout = flushTimeout // set before the handler becomes visible to crashing threads
            Thread.setDefaultUncaughtExceptionHandler(handler)
            installed = handler
        }

        /** Restores the handler that was active before [install], if this one is still the default. Tests only. */
        @Synchronized
        internal fun uninstallForTest() {
            startWorker = DEFAULT_START_WORKER
            val handler = installed ?: return
            if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
                Thread.setDefaultUncaughtExceptionHandler(handler.previous)
            }
            installed = null
        }
    }
}
