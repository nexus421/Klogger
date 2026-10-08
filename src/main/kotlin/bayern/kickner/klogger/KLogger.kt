package bayern.kickner.klogger

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


/**
 * Simple, configurable logging as a singleton with a small DSL for destinations.
 *
 * Example usage:
 * Logger.configure {
 *   logToConsole()                    // log to stdout (INFO and below), stderr for ERROR/CRASH
 *   // optionally in addition or instead:
 *   logToFile(File("logs/app.log"))  // append to file (file and parent dirs are created if missing)
 *   // or a custom destination:
 *   logToCustom { level, tag, message -> println("CUSTOM $level/$tag: $message") }
 *   // configuration flags:
 *   minLevel = KLogger.Level.DEBUG     // only messages >= minLevel are processed (unless debug = true)
 *   debug = false                     // if true, minLevel is ignored and everything is logged
 * }
 *
 * Tag handling: by default the simple class name is used as TAG (see DEFAULT_LOG_TAG).
 * You can pass your own tag by calling Logger methods directly.
 *
 * SLF4J: if anything in the application logs via SLF4J 2.x (for example Ktor), Klogger takes over that output
 * automatically and writes it to the configured destinations. See [bayern.kickner.klogger.slf4j.slf4jBridge].
 */
object KLogger {
    /**
     * Log levels in increasing severity.
     */
    enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    /**
     * Builds a line format for console and file destinations, to be assigned to [LoggerDsl.format].
     *
     * Output: `<timestamp> LEVEL/Tag: message`, or `<timestamp> [thread] LEVEL/Tag: message` with [threadName].
     * Example with milliseconds and thread name: `08.10.2026 14:03:12.345 [main] INFO/Handler: started`.
     *
     * @param timestampPattern [DateTimeFormatter] pattern in the system time zone, e.g. `"dd.MM.yyyy HH:mm:ss.SSS"`
     *   for milliseconds or `"yyyy-MM-dd'T'HH:mm:ss.SSSXXX"` for ISO-8601 with offset. Its syntax is validated right
     *   away, so an invalid pattern fails inside `configure` instead of on every log call.
     * @param threadName If true, the name of the logging thread is added in square brackets.
     * @throws IllegalArgumentException if [timestampPattern] is invalid.
     */
    fun defaultFormat(
        timestampPattern: String = "dd.MM.yyyy HH:mm:ss",
        threadName: Boolean = false,
    ): (level: Level, tag: String, message: String) -> String {
        val formatter = DateTimeFormatter.ofPattern(timestampPattern)
        return { level, tag, message ->
            // ZonedDateTime rather than LocalDateTime: same output for plain patterns, and zone/offset
            // patterns (XXX, z, VV) work instead of throwing on every line
            val ts = ZonedDateTime.now().format(formatter)
            if (threadName) "$ts [${Thread.currentThread().name}] ${level.name}/$tag: $message"
            else "$ts ${level.name}/$tag: $message"
        }
    }

    // Must stay above `config`: Config() reads it as its default format during object initialization.
    private val DEFAULT_FORMAT = defaultFormat()

    /** Formats a line in the default format `dd.MM.yyyy HH:mm:ss LEVEL/Tag: message` (local time). */
    fun formatLogDefault(level: Level, tag: String, message: String): String = DEFAULT_FORMAT(level, tag, message)

    /**
     * Formats a line for console and file destinations with the configured [LoggerDsl.format].
     * If that format throws, the line is formatted with [formatLogDefault] instead of being lost.
     */
    internal fun format(level: Level, tag: String, message: String): String =
        runCatching { config.format(level, tag, message) }.getOrElse { formatLogDefault(level, tag, message) }

    /**
     * Runtime configuration of the logger.
     *
     * @property destinations Thread-safe list of destinations (CopyOnWriteArrayList for concurrent access).
     * @property debug If true, minLevel filtering is bypassed.
     * @property minLevel Only messages >= this level are processed, unless debug is true.
     * @property format Line format for console and file destinations. See [LoggerDsl.format].
     * @property flushOnShutdown Timeout of the JVM shutdown hook flush, `null` = no hook. See [LoggerDsl.flushOnShutdown].
     */
    internal data class Config(
        val destinations: MutableList<Destination> = CopyOnWriteArrayList(),
        var debug: Boolean = false,
        var minLevel: Level = Level.DEBUG,
        var format: (level: Level, tag: String, message: String) -> String = DEFAULT_FORMAT,
        var flushOnShutdown: Duration? = null,
    )

    @Volatile
    private var config: Config = Config()

    /** Resets all state to defaults. Intended for use in unit tests only. */
    internal fun resetForTest() {
        config = Config()
        checkIfLoggingIsConfigured = false
        syncShutdownHook()
    }

    /**
     * Configure the logger using a small DSL. Existing destinations are kept,
     * new destinations can be added. The configuration reference is @Volatile,
     * so swapping it is safe for concurrent readers.
     *
     * This method is `@Synchronized` to prevent lost updates when called concurrently.
     * In practice, configure should only be called once at application startup.
     */
    @Synchronized
    fun configure(block: LoggerDsl.() -> Unit) {
        val dsl = LoggerDsl(config.copy(destinations = CopyOnWriteArrayList(config.destinations)))
        dsl.block()
        config = dsl.build()
        syncShutdownHook()
    }

    /**
     * Delivers everything the destinations still buffer and waits for at most [timeout].
     *
     * Only the Loki and HTTP appenders buffer entries; console, file, custom and cached forwarding
     * destinations write synchronously and need no flush. Typical uses: before the process exits
     * (see [LoggerDsl.flushOnShutdown]), after a crash (see [LoggerDsl.logUncaughtExceptions]) or on
     * Android in `onStop`, where no JVM shutdown happens.
     *
     * The destinations are flushed on a short-lived daemon thread while the caller waits at most [timeout]
     * for it. So [timeout] is a hard upper bound even if a request hangs (e.g. in DNS resolution), and
     * calling this on the Android main thread does not cause a `NetworkOnMainThreadException`. The caller is
     * still blocked for up to [timeout], so on Android prefer calling it from a background thread, e.g.
     * `lifecycleScope.launch(Dispatchers.IO) { KLogger.flush() }`.
     *
     * Entries logged while or after the flush runs are not guaranteed to be included. On an interrupted thread it
     * returns `false` right away (the flush carries on in the background) and keeps the interrupt status.
     *
     * Batches that fail to send are dropped and reported on stderr, as in the regular flush loop; the result
     * only says whether the buffers were emptied in time.
     *
     * @param timeout Maximum time to wait. Must be positive.
     * @return `true` if every buffer was emptied within [timeout] (appenders stopped via `scope.cancel()` count as
     *   empty, they send nothing anymore), `false` if the time ran out first. Entries not
     *   taken yet stay buffered for the regular flush loop; a batch whose request was cut short by the timeout
     *   is dropped like any failed batch.
     * @throws IllegalArgumentException if [timeout] is not positive.
     */
    fun flush(timeout: Duration = 3.seconds): Boolean {
        require(timeout.isPositive()) { "timeout must be positive, was $timeout" }
        val destinations = config.destinations.toList()
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val complete = AtomicBoolean(true)
        val worker = runCatching {
            thread(isDaemon = true, name = "klogger-flush") {
                for (dest in destinations) {
                    val remaining = -deadline.elapsedNow()
                    if (!remaining.isPositive()) {
                        complete.set(false)
                        break
                    }
                    // A throwing flush counts as incomplete: nothing confirms its buffer was emptied
                    if (!runCatching { dest.flush(remaining) }.getOrDefault(false)) complete.set(false)
                }
            }
        }.getOrElse { return false } // e.g. OutOfMemoryError: unable to create native thread
        try {
            worker.join(timeout.inWholeMilliseconds.coerceAtLeast(1)) // join(0) would wait forever
        } catch (_: InterruptedException) {
            // Never throw into the caller; restore the status that join() cleared so the caller can still react
            Thread.currentThread().interrupt()
            return false
        }
        return !worker.isAlive && complete.get()
    }

    /** The registered shutdown hook, `null` if [LoggerDsl.flushOnShutdown] is off. Changed by [configure] and `resetForTest`. */
    @Volatile
    private var shutdownHook: Thread? = null

    internal val shutdownHookForTest: Thread? get() = shutdownHook

    /**
     * Registers or removes the JVM shutdown hook so that it matches [Config.flushOnShutdown].
     * There is never more than one hook, no matter how often [configure] runs.
     */
    private fun syncShutdownHook() {
        val wanted = config.flushOnShutdown != null
        val hook = shutdownHook
        if (wanted && hook == null) {
            // Reads the timeout when the hook runs, so a later configure { flushOnShutdown = ... } takes effect.
            val newHook = Thread({ config.flushOnShutdown?.let { flush(it) } }, "klogger-shutdown-flush")
            runCatching { Runtime.getRuntime().addShutdownHook(newHook) }
                .onSuccess { shutdownHook = newHook }
                .onFailure { System.err.println("KLogger: could not register shutdown hook – $it") }
        } else if (!wanted && hook != null) {
            // Throws IllegalStateException if the JVM is already shutting down - nothing left to undo then.
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            shutdownHook = null
        }
    }


    /** Log a DEBUG message. */
    fun debug(tag: String, msg: () -> String) = log(Level.DEBUG, tag, msg)

    /** Log an INFO message. */
    fun info(tag: String, msg: () -> String) = log(Level.INFO, tag, msg)

    /** Log a WARN message. */
    fun warn(tag: String, msg: () -> String) = log(Level.WARN, tag, msg)

    /** Log an ERROR message. */
    fun error(tag: String, msg: () -> String) = log(Level.ERROR, tag, msg)

    /** Log a CRASH message (like ERROR, but separate level). */
    fun crash(tag: String, msg: () -> String) = log(Level.CRASH, tag, msg)

    /**
     * Returns true if a message of [level] would currently be dispatched, honouring [LoggerDsl.minLevel]
     * and [LoggerDsl.debug]. The SLF4J bridge uses it to answer `isDebugEnabled()` and friends correctly,
     * so callers that guard expensive log statements skip them entirely.
     */
    fun isEnabled(level: Level): Boolean {
        val cfg = config
        return cfg.debug || level.ordinal >= cfg.minLevel.ordinal
    }

    @Volatile
    private var checkIfLoggingIsConfigured = false

    /**
     * Internal dispatcher that reads the current config and writes to all destinations.
     * Errors in destinations are caught so logging never interferes with the app.
     */
    private fun log(level: Level, tag: String, msg: () -> String) {
        if (isEnabled(level).not()) return
        val message = msg()
        // Prints a hint one time if logging is used but not configured.
        if (!checkIfLoggingIsConfigured && config.destinations.isEmpty()) {
            System.err.println("No destinations configured. No Logging. Use Logger.configure { ... } to add destinations.")
            checkIfLoggingIsConfigured = true
        }
        config.destinations.forEach { dest ->
            runCatching { dest.log(level, tag, message) }
        }
    }
}
