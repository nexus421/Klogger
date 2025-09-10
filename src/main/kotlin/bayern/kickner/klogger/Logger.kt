package bayern.kickner.klogger

import java.io.File
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
object Logger {
    /**
     * Log levels in increasing severity.
     */
    enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    /**
     * A destination for log output.
     */
    interface Destination {
        /**
         * Writes a log message to the destination.
         * Implementations must be thread-safe if they mutate shared state.
         */
        fun log(level: Level, tag: String, message: String)
    }

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    fun formatLogDefault(level: Level, tag: String, message: String): String {
        val ts = LocalDateTime.now().format(DATE_FMT)
        return "$ts ${level.name}/$tag: $message"
    }

    /**
     * Console destination (stdout for DEBUG/INFO/WARN, stderr for ERROR/CRASH).
     */
    private class ConsoleDestination : Destination {
        override fun log(level: Level, tag: String, message: String) {
            if (level == Level.ERROR || level == Level.CRASH) System.err.println(formatLogDefault(level, tag, message))
            else println(formatLogDefault(level, tag, message))
        }
    }

    /**
     * File destination (append). Thread-safe by using @Synchronized.
     */
    private class FileDestination(private val file: File) : Destination {
        init {
            file.parentFile?.mkdirs()
            if (file.exists().not()) file.createNewFile()
        }

        @Synchronized
        override fun log(level: Level, tag: String, message: String) {
            file.appendText(formatLogDefault(level, tag, message) + System.lineSeparator())
        }
    }

    /**
     * Destination backed by a lambda function.
     */
    private class LambdaDestination(private val block: (level: Level, tag: String, message: String) -> Unit) :
        Destination {
        override fun log(level: Level, tag: String, message: String) = block(level, tag, message)
    }

    /**
     * Very simple file-backed queue for log entries. One file per entry, persisted on disk.
     * File format (UTF-8):
     *   line1 = LEVEL name
     *   line2 = TAG
     *   line3..n = MESSAGE (may contain newlines)
     */
    private class FileBackedLogQueue(private val dir: File) {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        init {
            dir.mkdirs()
        }

        fun isEmpty() = dir.list()?.isEmpty() ?: true

        @Synchronized
        fun enqueue(level: Level, tag: String, message: String) {
            val name = System.currentTimeMillis().toString() + "_" + counter.getAndIncrement() + ".logq"
            val file = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(level.name + "\n" + tag + "\n" + message)
            tmp.renameTo(file)
        }
        @Synchronized
        fun listFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".logq") }?.sortedBy { it.name } ?: emptyList()
        @Synchronized
        fun read(file: File): Triple<Level, String, String> {
            val lines = file.readLines()
            val lvl = Level.valueOf(lines.first())
            val tag = if (lines.size >= 2) lines[1] else ""
            val msg = if (lines.size >= 3) lines.drop(2).joinToString("\n") else ""
            return Triple(lvl, tag, msg)
        }
        @Synchronized
        fun remove(file: File) { file.delete() }
    }

    /**
     * Destination that forwards logs to another destination. If forwarding fails, the log is cached in a
     * file-backed queue. On each log attempt, it also tries to flush cached logs.
     */
    private class CachedForwardingDestination(
        private val target: Destination,
        private val queue: FileBackedLogQueue,
        private val maxFlushPerCall: Int = 10,
    ) : Destination {
        override fun log(level: Level, tag: String, message: String) {
            // First try to forward the current log
            val forwarded = try {
                target.log(level, tag, message)
                true
            } catch (_: Throwable) {
                false
            }
            if (!forwarded) {
                // Cache it for later
                runCatching { queue.enqueue(level, tag, message) }
            }
            // Opportunistically try to flush some cached logs
            tryFlush()
        }
        private fun tryFlush() {
            if(queue.isEmpty()) return

            val files = runCatching { queue.listFiles() }.getOrDefault(emptyList())
            var count = 0
            for (f in files) {
                if (count >= maxFlushPerCall) break
                val (lvl, tg, msg) = try {
                    queue.read(f)
                } catch (_: Throwable) {
                    // Corrupt entry, drop it
                    runCatching { queue.remove(f) }
                    continue
                }
                val logProcessedSuccessful = try {
                    target.log(lvl, tg, msg)
                    true
                } catch (_: Throwable) {
                    false
                }
                if (logProcessedSuccessful) {
                    runCatching { queue.remove(f) }
                    count++
                } else {
                    // If we cannot send the oldest, stop to avoid busy loops
                    break
                }
            }
        }
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

    /**
     * Internal dispatcher that reads the current config and writes to all destinations.
     * Errors in destinations are caught so logging never interferes with the app.
     */
    private fun log(level: Level, tag: String, msg: () -> String) {
        val current = config
        if (current.debug.not() && level.ordinal < current.minLevel.ordinal) return // filtered by minLevel: we do not even evaluate the message lambda
        val message = msg()
        current.destinations.forEach { dest ->
            runCatching { dest.log(level, tag, message) }
        }
    }


    /**
     * DSL to configure the logger.
     */
    class LoggerDsl internal constructor(private val cfg: Config) {
        /** Adds console output as a destination. */
        fun logToConsole() {
            cfg.destinations.add(ConsoleDestination())
        }

        /** Adds a file output destination. */
        fun logToFile(path: String) {
            cfg.destinations.add(FileDestination(File(path)))
        }

        /** Adds a custom (lambda) destination. */
        fun logToCustom(block: (level: Level, tag: String, message: String) -> Unit) {
            cfg.destinations.add(LambdaDestination(block))
        }

        /**
         * Adds a destination that caches logs persistently and forwards to a provided lambda target.
         * If forwarding fails (exception), logs are stored in cacheDir and retried on subsequent calls.
         */
        fun logToCachedForwarding(
            cacheDirPath: String,
            target: (level: Level, tag: String, message: String) -> Unit,
            maxFlushPerCall: Int = 10
        ) {
            val queue = FileBackedLogQueue(File(cacheDirPath))
            val targetDest = LambdaDestination(target)
            cfg.destinations.add(CachedForwardingDestination(targetDest, queue, maxFlushPerCall))
        }

        /**
         * Set the minimum log level (DEBUG < INFO < WARN < ERROR < CRASH).
         */
        var minLevel: Level
            get() = cfg.minLevel
            set(value) {
                cfg.minLevel = value
            }

        internal fun build(): Config = cfg

        /**
         * If set to true, the [minLevel] will be ignored.
         */
        var debug: Boolean
            get() = cfg.debug
            set(value) {
                cfg.debug = value
            }
    }
}


/**
 * Default log tag: simple class name of the calling instance.
 */
val Any.DEFAULT_LOG_TAG: String
    get() = this::class.simpleName ?: "Unknown"

/**
 * Use this only, if you want to log something within a static file.
 * Within static files, we don't have [Any.DEFAULT_LOG_TAG]
 */
fun staticDebugLog(level: Logger.Level, tag: String, msg: () -> String) {
    when (level) {
        Logger.Level.DEBUG -> Logger.debug(tag, msg)
        Logger.Level.INFO -> Logger.info(tag, msg)
        Logger.Level.WARN -> Logger.warn(tag, msg)
        Logger.Level.ERROR -> Logger.error(tag, msg)
        Logger.Level.CRASH -> Logger.crash(tag, msg)
    }
}

inline fun <reified T : Any> T.debugLog(noinline msg: () -> String) = Logger.debug(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.infoLog(noinline msg: () -> String) = Logger.info(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.warnLog(noinline msg: () -> String) = Logger.warn(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.errorLog(
    noinline msg: () -> String,
    ex: Throwable? = null,
    printStackTrace: Boolean = true,
    sendAsCrash: Boolean = false
) {
    val logBlock: () -> String = {
        val base = msg()
        val suffix = ex?.let {
            "\n" + if (printStackTrace) it.stackTraceToString() else ("Exception: " + (it.message
                ?: it::class.simpleName))
        } ?: ""
        base + suffix
    }
    if (sendAsCrash) Logger.crash(DEFAULT_LOG_TAG, logBlock) else Logger.error(DEFAULT_LOG_TAG, logBlock)
}

inline fun <reified T : Any> T.errorLog(ex: Throwable) = Logger.error(DEFAULT_LOG_TAG) { ex.stackTraceToString() }

