package bayern.kickner.klogger

import java.io.File

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
     */
    fun logToConsole() {
        cfg.destinations.add(ConsoleDestination())
    }

    /**
     * Sets a new file as a logging destination through [FileDestination].
     *
     * This method configures the logger to write log messages to the specified file.
     * The file will be created if it does not exist, and log messages will be appended
     * to it in a thread-safe manner.
     *
     * @param destinationLogFile The file where log messages will be written.
     */
    fun logToFile(destinationLogFile: File) {
        cfg.destinations.add(FileDestination(destinationLogFile))
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
     * @throws IllegalArgumentException if [maxFlushPerCall] or [maxQueueSize] is below 1.
     */
    fun logToCachedForwarding(
        cacheDirPath: String,
        target: (level: KLogger.Level, tag: String, message: String) -> Unit,
        maxFlushPerCall: Int = 10,
        maxQueueSize: Int = 500
    ) {
        val queue = FileBackedLogQueue(File(cacheDirPath), maxQueueSize)
        val targetDest = LambdaDestination(target)
        cfg.destinations.add(CachedForwardingDestination(targetDest, queue, maxFlushPerCall))
    }

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
     * If set to true, the [minLevel] will be ignored and all type of logs will be printed.
     */
    var debug: Boolean
        get() = cfg.debug
        set(value) {
            cfg.debug = value
        }
}