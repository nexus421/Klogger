package bayern.kickner.klogger

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Simple, configurable logging as a singleton with a small DSL for destinations.
 *
 * Example usage:
 * KLogger.configure {
 *   logToConsole()                    // log to stdout (INFO and below), stderr for ERROR/CRASH
 *   // optionally in addition or instead:
 *   logToFile("logs/app.log")         // append to file (file and parent dirs are created if missing)
 *   // or a custom destination:
 *   logToCustom { level, tag, message -> println("CUSTOM $level/$tag: $message") }
 *   // configuration flags:
 *   minLevel = KLogger.Level.DEBUG     // only messages >= minLevel are processed (unless debug = true)
 *   debug = false                     // if true, minLevel is ignored and everything is logged
 * }
 *
 * Tag handling: by default the simple class name is used as TAG (see DEFAULT_LOG_TAG).
 * You can pass your own tag by calling KLogger methods directly.
 */
object KLogger {
    /**
     * Log levels in increasing severity.
     */
    enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    fun formatLogDefault(level: Level, tag: String, message: String): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val d = now.dayOfMonth.toString().padStart(2, '0')
        val m = now.monthNumber.toString().padStart(2, '0')
        val y = now.year.toString().padStart(4, '0')
        val h = now.hour.toString().padStart(2, '0')
        val min = now.minute.toString().padStart(2, '0')
        val s = now.second.toString().padStart(2, '0')
        val ts = "$d.$m.$y $h:$min:$s"
        return "$ts ${level.name}/$tag: $message"
    }

    /**
     * Runtime configuration of the logger.
     * - destinations: thread-safe list of destinations
     * - debug: if true, bypasses minLevel filtering
     * - minLevel: only messages >= this level will be processed (unless debug is true)
     */
    internal data class Config(
        val destinations: List<Destination> = emptyList(),
        var debug: Boolean = false,
        var minLevel: Level = Level.DEBUG,
    )

    private val configRef = AtomicReference(Config())
    private val configureLock = Lock()
    private val checkIfLoggingIsConfigured = AtomicInt(0)

    /** Resets all state to defaults. Intended for use in unit tests only. */
    internal fun resetForTest() {
        configureLock.withLock {
            configRef.store(Config())
            checkIfLoggingIsConfigured.store(0)
        }
    }

    /**
     * Configure the logger using a small DSL. Existing destinations are kept,
     * new destinations can be added. Swapping the configuration is safe for concurrent readers.
     *
     * This method is synchronized to prevent lost updates when called concurrently.
     * In practice, configure should only be called once at application startup.
     */
    fun configure(block: LoggerDsl.() -> Unit) {
        configureLock.withLock {
            val current = configRef.load()
            val dsl = LoggerDsl(current.copy(destinations = current.destinations))
            dsl.block()
            configRef.store(dsl.build())
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
     * Internal dispatcher that reads the current config and writes to all destinations.
     * Errors in destinations are caught so logging never interferes with the app.
     */
    private fun log(level: Level, tag: String, msg: () -> String) {
        val currentConfig = configRef.load()
        if (!currentConfig.debug && level.ordinal < currentConfig.minLevel.ordinal) return
        val message = msg()
        // Prints a hint one time if logging is used but not configured.
        if (currentConfig.destinations.isEmpty() && checkIfLoggingIsConfigured.compareAndSet(0, 1)) {
            printStderr("No destinations configured. No Logging. Use Logger.configure { ... } to add destinations.")
        }
        currentConfig.destinations.forEach { dest ->
            runCatching { dest.log(level, tag, message) }
        }
    }
}
