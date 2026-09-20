package bayern.kickner.klogger

/**
 * DSL to configure the logger.
 */
class LoggerDsl internal constructor(private val cfg: KLogger.Config) {
    private val destinations = cfg.destinations.toMutableList()

    /**
     * Adds the console as a logging destination using [ConsoleDestination].
     */
    fun logToConsole() {
        destinations.add(ConsoleDestination())
    }

    /**
     * Sets a new file path as a logging destination through [FileDestination].
     */
    fun logToFile(path: String) {
        destinations.add(FileDestination(path))
    }

    /**
     * Adds a custom logging destination using a lambda function through [LambdaDestination].
     */
    fun logToCustom(block: (level: KLogger.Level, tag: String, message: String) -> Unit) {
        destinations.add(LambdaDestination(block))
    }

    /**
     * Registers a destination instance unless that same instance is already registered.
     */
    internal fun logTo(destination: Destination) {
        if (destination !in destinations) destinations.add(destination)
    }

    /**
     * Configures a destination that caches log messages to the local filesystem if the target
     * destination fails to handle them, using a file-backed queue for persistence.
     */
    fun logToCachedForwarding(
        cacheDirPath: String,
        target: (level: KLogger.Level, tag: String, message: String) -> Unit,
        maxFlushPerCall: Int = 10,
        maxQueueSize: Int = 500
    ) {
        val queue = FileBackedLogQueue(cacheDirPath, maxQueueSize)
        val targetDest = LambdaDestination(target)
        destinations.add(CachedForwardingDestination(targetDest, queue, maxFlushPerCall))
    }

    /**
     * Set the minimum log level that should be printed (DEBUG < INFO < WARN < ERROR < CRASH).
     */
    var minLevel: KLogger.Level
        get() = cfg.minLevel
        set(value) {
            cfg.minLevel = value
        }

    internal fun build(): KLogger.Config = cfg.copy(destinations = destinations.toList())

    /**
     * If set to true, the [minLevel] will be ignored and all type of logs will be printed.
     */
    var debug: Boolean
        get() = cfg.debug
        set(value) {
            cfg.debug = value
        }
}
