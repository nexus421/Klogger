package bayern.kickner.klogger

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DSL to configure the logger.
 */
class LoggerDsl internal constructor(private val cfg: KLogger.Config) {

    /**
     * Adds the console as a logging destination using [ConsoleDestination].
     *
     * This method configures the logger to write log messages to the console.
     * Log messages with levels `ERROR` and `CRASH` will be directed to `System.err`,
     * while other levels will be written to standard output (`System.out`).
     *
     * @param minLevel Only messages at or above this level are printed by this destination, on top of the
     *   global [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true. `null` (default) = no extra filter.
     */
    fun logToConsole(minLevel: KLogger.Level? = null) {
        cfg.destinations.add(ConsoleDestination().withMinLevel(minLevel))
    }

    /**
     * Sets a new file as a logging destination through [FileDestination].
     *
     * This method configures the logger to write log messages to the specified file.
     * The file will be created if it does not exist, and log messages will be appended
     * to it in a thread-safe manner.
     *
     * Optional size-based rotation: once the file would exceed [maxFileSize] bytes, `app.log` becomes
     * `app.log.1`, `app.log.1` becomes `app.log.2` and so on, keeping at most [maxBackupFiles] backups.
     *
     * Example: `logToFile(File("logs/app.log"), maxFileSize = 5L * 1024 * 1024, maxBackupFiles = 3)`
     *
     * @param destinationLogFile The file where log messages will be written.
     * @param maxFileSize Rotate before the file would exceed this many bytes. `null` (default) = never rotate.
     * @param maxBackupFiles Number of rotated files kept (`app.log.1` is the newest). Default 3.
     * @param minLevel Only messages at or above this level are written to this file, on top of the global
     *   [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true. `null` (default) = no extra filter.
     * @throws IllegalArgumentException if [maxFileSize] is not > 0 or [maxBackupFiles] is below 1.
     */
    fun logToFile(
        destinationLogFile: File,
        maxFileSize: Long? = null,
        maxBackupFiles: Int = 3,
        minLevel: KLogger.Level? = null,
    ) {
        cfg.destinations.add(FileDestination(destinationLogFile, maxFileSize, maxBackupFiles).withMinLevel(minLevel))
    }

    /**
     * Adds a custom logging destination using a lambda function through [LambdaDestination].
     *
     * This method allows you to define a custom behavior for processing log messages by supplying
     * a lambda function. The lambda will be executed every time a log message is dispatched, with
     * the log level, tag, and message as its input parameters. The custom destination can be used
     * alongside or instead of other predefined destinations.
     *
     * @param block A lambda function that takes the log level, tag, and message as arguments and
     * processes the log message.
     */
    fun logToCustom(block: (level: KLogger.Level, tag: String, message: String) -> Unit) {
        cfg.destinations.add(LambdaDestination(block))
    }

    /**
     * Like [logToCustom], but [block] only receives messages at or above [minLevel], on top of the global
     * [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true.
     *
     * Example: `logToCustom(KLogger.Level.WARN) { level, tag, message -> alerts.send("$level $tag: $message") }`
     *
     * (An overload rather than an optional parameter, so existing calls like `logToCustom(mySink)` keep compiling.)
     */
    fun logToCustom(
        minLevel: KLogger.Level,
        block: (level: KLogger.Level, tag: String, message: String) -> Unit,
    ) {
        cfg.destinations.add(LambdaDestination(block).withMinLevel(minLevel))
    }

    /**
     * Registers a destination instance unless that same instance is already registered. Used by
     * appenders that keep one stable destination, so re-registering them (e.g. calling `logToHttp`
     * again with new settings) doesn't deliver every log line twice.
     */
    internal fun logTo(destination: Destination) {
        if (destination !in cfg.destinations) cfg.destinations.add(destination)
    }

    /**
     * Configures a destination that caches log messages to the local filesystem if the target
     * destination fails to handle them, using a file-backed queue for persistence. On each invocation,
     * this method also attempts to forward cached messages to the target destination, oldest first,
     * so chronological order is preserved even while a backlog exists.
     *
     * @param cacheDirPath The directory path where log messages will be cached if forwarding fails.
     *                     Cached messages are stored persistently in this directory until they
     *                     are successfully forwarded.
     * @param target A lambda function specifying the target destination for logs. The lambda takes
     *               three arguments: the log level, tag, and message. This destination will receive
     *               log messages directly or flushed from the cache.
     * @param maxFlushPerCall The maximum number of cached log messages to attempt to flush during
     *                        a single invocation of the logging mechanism. Must be >= 1. Defaults to 10.
     * @param maxQueueSize The maximum number of cached entries kept on disk. Once exceeded, the
     *                     oldest cached entries are dropped to make room for new ones. Must be >= 1.
     *                     Defaults to 500.
     * @param minLevel Only messages at or above this level are forwarded or cached, on top of the global
     *                 [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true. `null` (default) = no extra filter.
     * @throws IllegalArgumentException if [maxFlushPerCall] or [maxQueueSize] is below 1.
     */
    fun logToCachedForwarding(
        cacheDirPath: String,
        target: (level: KLogger.Level, tag: String, message: String) -> Unit,
        maxFlushPerCall: Int = 10,
        maxQueueSize: Int = 500,
        minLevel: KLogger.Level? = null,
    ) {
        val queue = FileBackedLogQueue(File(cacheDirPath), maxQueueSize)
        val targetDest = LambdaDestination(target)
        cfg.destinations.add(CachedForwardingDestination(targetDest, queue, maxFlushPerCall).withMinLevel(minLevel))
    }

    /** Wraps this destination in a [LevelFilterDestination] if [minLevel] is set. */
    private fun Destination.withMinLevel(minLevel: KLogger.Level?): Destination =
        if (minLevel == null) this else LevelFilterDestination(minLevel, this)

    /**
     * Set the minimum log level that should be printed (DEBUG < INFO < WARN < ERROR < CRASH).
     */
    var minLevel: KLogger.Level
        get() = cfg.minLevel
        set(value) {
            cfg.minLevel = value
        }

    internal fun build(): KLogger.Config = cfg

    /**
     * Sets the line format of the console and file destinations. Default: [KLogger.formatLogDefault], i.e.
     * `dd.MM.yyyy HH:mm:ss LEVEL/Tag: message`.
     *
     * Use [KLogger.defaultFormat] for milliseconds or the thread name, or any lambda for a fully custom format:
     * ```
     * format(KLogger.defaultFormat("dd.MM.yyyy HH:mm:ss.SSS", threadName = true))
     * format { level, tag, message -> "[$level] $tag: $message" }
     * ```
     * [lineFormat] runs on the logging thread for every console/file line. If it throws, the line is written in
     * the default format instead. Custom, cached forwarding, Loki and HTTP destinations receive the raw
     * message and are not affected.
     *
     * (A function rather than a `format` property: a property would shadow a `format` member of the class
     * that calls `configure { }`, and silently change what existing code inside the block refers to.)
     */
    fun format(lineFormat: (level: KLogger.Level, tag: String, message: String) -> String) {
        cfg.format = lineFormat
    }

    /**
     * Sends buffered log entries when the JVM shuts down: if set, a shutdown hook calls [KLogger.flush] with
     * this timeout, so the last Loki/HTTP batches are not lost on exit. `null` (the default) registers no hook,
     * and setting it back to `null` removes the hook again.
     *
     * The hook runs on a normal exit, `System.exit` and SIGTERM/SIGINT, but not on SIGKILL or `Runtime.halt`.
     *
     * Leave it off on Android: Android apps are killed without a regular VM shutdown, so the hook would
     * practically never run. Call [KLogger.flush] from a lifecycle callback such as `onStop` instead.
     *
     * Example: `flushOnShutdown = 3.seconds`
     *
     * @throws IllegalArgumentException if set to a duration that is not positive.
     */
    var flushOnShutdown: Duration?
        get() = cfg.flushOnShutdown
        set(value) {
            require(value == null || value.isPositive()) { "flushOnShutdown must be positive, was $value" }
            cfg.flushOnShutdown = value
        }

    /**
     * Logs every uncaught exception as [KLogger.Level.CRASH] (tag `UncaughtException`, with thread name and stack
     * trace), flushes buffering destinations and then hands the exception to the default handler that was
     * installed before. Logging and flushing together take at most [flushTimeout]; even a destination that hangs
     * cannot keep the crash from the previous handler. Logging runs on a helper thread `klogger-crash` (with
     * `defaultFormat(threadName = true)` the line shows that name; the crashed thread is named in the message).
     * If no thread can be started (e.g. `OutOfMemoryError: unable to create native thread`), the crash is logged on
     * the crashing thread instead, without flush. Crashes of threads that a destination starts while logging a
     * crash are handed on but not logged again, so a broken destination cannot cause an endless crash-log loop. The platform's crash behaviour stays intact: the JVM still
     * prints the exception, Android still shows its crash dialog and kills the process, and crash reporters
     * such as Crashlytics still see the crash.
     *
     * Not active unless called. Installs a JVM-wide default handler right away (also if the rest of the
     * `configure` block throws); it is installed only once, so calling this again only updates [flushTimeout].
     * A handler installed later by another library runs first and reaches this one only if it passes the
     * exception on to the previous handler, as crash reporters do.
     *
     * @param flushTimeout Upper bound for logging and flushing the crash. Must be positive.
     * @throws IllegalArgumentException if [flushTimeout] is not positive.
     */
    fun logUncaughtExceptions(flushTimeout: Duration = 2.seconds) {
        require(flushTimeout.isPositive()) { "flushTimeout must be positive, was $flushTimeout" }
        KloggerUncaughtExceptionHandler.install(flushTimeout)
    }

    /**
     * If set to true, the [minLevel] will be ignored and all type of logs will be printed.
     */
    var debug: Boolean
        get() = cfg.debug
        set(value) {
            cfg.debug = value
        }
}