package bayern.kickner.klogger

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList


/**
 * Simple, configurable logging as a singleton with a small DSL for destinations.
 *
 * Example usage:
 * Logger.configure {
 *   logToConsole()                    // log to stdout (INFO and below), stderr for ERROR/CRASH
 *   // optionally in addition or instead:
 *   logToFile("logs/app.log")        // append to file (file and parent dirs are created if missing)
 *   // or a custom destination:
 *   logToCustom { level, tag, message -> println("CUSTOM $level/$tag: $message") }
 *   // configuration flags:
 *   minLevel = Logger.Level.DEBUG     // only messages >= minLevel are processed (unless debug = true)
 *   debug = false                     // if true, minLevel is ignored and everything is logged
 * }
 *
 * Tag handling: by default the simple class name is used as TAG (see DEFAULT_LOG_TAG).
 * You can pass your own tag by calling Logger methods directly.
 */
object KLogger {
    /**
     * Log levels in increasing severity.
     */
    enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    fun formatLogDefault(level: Level, tag: String, message: String): String {
        val ts = LocalDateTime.now().format(DATE_FMT)
        return "$ts ${level.name}/$tag: $message"
    }

    /**
     * Runtime configuration of the logger.
     * - destinations: thread-safe list of destinations (CopyOnWriteArrayList for concurrent access)
     * - debug: if true, bypasses minLevel filtering
     * - minLevel: only messages >= this level will be processed (unless debug is true)
     */
    internal data class Config(
        val destinations: MutableList<Destination> = CopyOnWriteArrayList(),
        var debug: Boolean = false,
        var minLevel: Level = Level.DEBUG,
    )

    @Volatile
    private var config: Config = Config()

    /**
     * Configure the logger using a small DSL. Existing destinations are kept,
     * new destinations can be added. The configuration reference is @Volatile,
     * so swapping it is safe for concurrent readers.
     */
    fun configure(block: LoggerDsl.() -> Unit) {
        val dsl = LoggerDsl(config.copy(destinations = CopyOnWriteArrayList(config.destinations)))
        dsl.block()
        config = dsl.build()
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

    private var checkIfLoggingIsConfigured = false

    /**
     * Internal dispatcher that reads the current config and writes to all destinations.
     * Errors in destinations are caught so logging never interferes with the app.
     */
    private fun log(level: Level, tag: String, msg: () -> String) {
        if (config.debug.not() && level.ordinal < config.minLevel.ordinal) return
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
