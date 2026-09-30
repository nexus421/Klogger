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

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    fun formatLogDefault(level: Level, tag: String, message: String): String {
        val ts = LocalDateTime.now().format(DATE_FMT)
        return "$ts ${level.name}/$tag: $message"
    }

    /**
     * Runtime configuration of the logger.
     *
     * @property destinations Thread-safe list of destinations (CopyOnWriteArrayList for concurrent access).
     * @property debug If true, minLevel filtering is bypassed.
     * @property minLevel Only messages >= this level are processed, unless debug is true.
     */
    internal data class Config(
        val destinations: MutableList<Destination> = CopyOnWriteArrayList(),
        var debug: Boolean = false,
        var minLevel: Level = Level.DEBUG,
    )

    @Volatile
    private var config: Config = Config()

    /** Resets all state to defaults. Intended for use in unit tests only. */
    internal fun resetForTest() {
        config = Config()
        checkIfLoggingIsConfigured = false
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
