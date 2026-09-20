package bayern.kickner.klogger

import bayern.kickner.klogger.KLogger.Level
import bayern.kickner.klogger.KLogger.formatLogDefault
import java.io.File
import java.io.IOException

/**
 * Represents a logging destination where log messages can be written.
 *
 * Implementing classes define the specific behavior for how and where
 * log messages are output, such as to a file, console, or external service.
 * Implementations of this interface should ensure thread-safety when
 * accessing or modifying shared state.
 */
interface Destination {
    /**
     * Writes a log message to the destination.
     * Implementations must be thread-safe if they mutate shared state.
     */
    fun log(level: Level, tag: String, message: String)
}

/**
 * A logging destination that writes log messages to the console.
 *
 * Log messages with levels `ERROR` and `CRASH` are written to `System.err`,
 * while other levels are written to standard output (`System.out`).
 *
 * This class delegates message formatting to the `formatLogDefault` function.
 */
internal class ConsoleDestination : Destination {
    override fun log(level: Level, tag: String, message: String) {
        if (level == Level.ERROR || level == Level.CRASH) System.err.println(formatLogDefault(level, tag, message))
        else println(formatLogDefault(level, tag, message))
    }
}

/**
 * A logging destination that writes log messages to a specified file.
 *
 * This class is responsible for outputting log messages to a file. The file
 * and its parent directories are created if they do not already exist.
 * Log messages are appended to the file in a thread-safe manner.
 *
 * @constructor Initializes a new instance of the FileDestination class with the
 * specified file. If the file or its parent directories do not already
 * exist, they will be created.
 *
 * @param file The file to which log messages will be written.
 *
 * Behavior:
 * - Ensures the specified file and its parent directories are created if missing.
 * - Appends formatted log messages to the file.
 * - Implements thread-safe logging using the `@Synchronized` annotation on the log method.
 *
 * Methods:
 * - `log(level: Level, tag: String, message: String)`: Writes a formatted log message
 *   (with a timestamp, log level, and tag) to the file.
 */
internal class FileDestination(private val file: File) : Destination {
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
 * A logging destination that executes a lambda whenever a log message is dispatched.
 *
 * This class implements the [Destination] interface, allowing it to be used as a
 * custom logging destination within the `Logger` system. The lambda provided in the
 * constructor is invoked with the log level, tag, and message, enabling custom
 * handling of log output.
 *
 * @constructor Creates a `LambdaDestination` with the given lambda block.
 * @param block A lambda function that receives the log [Level], [tag], and [message]
 * and processes them as per the user's requirements.
 */
internal class LambdaDestination(private val block: (level: Level, tag: String, message: String) -> Unit) :
    Destination {
    override fun log(level: Level, tag: String, message: String) = block(level, tag, message)
}

/**
 * A thread-safe, file-backed queue for storing log messages. Each log message is stored in its
 * own file within the specified directory. The files are named based on the current timestamp
 * and a counter to ensure uniqueness.
 *
 * @constructor Creates a FileBackedLogQueue and ensures that the specified directory exists.
 * @param dir The directory where log files will be stored.
 */
internal class FileBackedLogQueue(private val dir: File, private val maxQueueSize: Int = 500) {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    init {
        require(maxQueueSize >= 1) { "maxQueueSize must be >= 1, was $maxQueueSize" }
        dir.mkdirs()
    }

    /** True if no queue entry exists. Only `.logq` files count - never `.tmp` or unrelated files. */
    @Synchronized
    fun isEmpty(): Boolean = listFiles().isEmpty()

    @Synchronized
    fun enqueue(level: Level, tag: String, message: String) {
        // Counter is zero-padded so filename sort order stays correct even once it reaches double
        // digits within the same millisecond (plain "9" would otherwise sort after "10" lexically).
        val name = System.currentTimeMillis().toString() + "_" +
                counter.getAndIncrement().toString().padStart(19, '0') + ".logq"
        val file = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        // The format is line-based (level / tag / message): a line break in the tag would shift
        // the message into the tag on read-back, so it is flattened. The message may span lines.
        val singleLineTag = tag.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        try {
            tmp.writeText(level.name + "\n" + singleLineTag + "\n" + message)
            // Signal a failure to the caller instead of logging it: logging from in here would
            // re-enter KLogger while holding the queue lock (and recurse via the cached forwarder).
            if (!tmp.renameTo(file)) throw IOException("FileBackedLogQueue: failed to persist cached log entry to $file")
        } catch (e: Throwable) {
            // Never leave a partial or orphaned .tmp behind (disk full mid-write, cross-filesystem
            // move, ...) - it would linger on disk forever.
            tmp.delete()
            throw e
        }
        trimToMaxSize()
    }

    /** Drops the oldest cached entries beyond [maxQueueSize] so the cache can't grow unbounded. */
    private fun trimToMaxSize() {
        val files = listFiles()
        val excess = files.size - maxQueueSize
        if (excess <= 0) return
        files.take(excess).forEach { runCatching { it.delete() } }
    }

    @Synchronized
    fun listFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".logq") }?.sortedBy { it.name } ?: emptyList()

    @Synchronized
    fun read(file: File): Triple<Level, String, String> {
        val lines = file.readLines()
        val lvl = Level.valueOf(lines.first())
        val tag = if (lines.size >= 2) lines[1] else ""
        val msg = if (lines.size >= 3) lines.drop(2).joinToString("\n") else ""
        return Triple(lvl, tag, msg)
    }

    @Synchronized
    fun remove(file: File) {
        file.delete()
    }
}

/**
 * Destination that forwards logs to another destination. If forwarding fails, the log is cached in a
 * file-backed queue. On each log attempt, it also tries to flush cached logs.
 */
internal class CachedForwardingDestination(
    private val target: Destination,
    private val queue: FileBackedLogQueue,
    private val maxFlushPerCall: Int = 10,
) : Destination {
    init {
        require(maxFlushPerCall >= 1) { "maxFlushPerCall must be >= 1, was $maxFlushPerCall" }
    }

    /**
     * Synchronized so concurrent [log] calls can't race on [tryFlush]: without this, two threads
     * could both read the same cached entry before either removes it, delivering it twice.
     */
    @Synchronized
    override fun log(level: Level, tag: String, message: String) {
        // Flush older cached entries first so delivery to the target preserves chronological
        // order instead of the newest message jumping ahead of older cached ones.
        tryFlush()
        val delivered = if (queue.isEmpty()) {
            // No (remaining) backlog: forward directly, cache on failure
            forward(level, tag, message) || cache(level, tag, message)
        } else {
            // Backlog remains (target still down, or more entries than maxFlushPerCall): queue
            // this entry behind it instead of forwarding it directly. If the cache itself is
            // unavailable (disk full, permissions, ...), deliver directly after all - a possible
            // order violation is preferable to losing the entry.
            cache(level, tag, message) || forward(level, tag, message)
        }
        // Never reported through KLogger: we are inside a destination, that would recurse
        if (!delivered) System.err.println("CachedForwardingDestination: dropped $level log '$tag' - target and cache both unavailable")
    }

    private fun forward(level: Level, tag: String, message: String): Boolean =
        runCatching { target.log(level, tag, message) }.isSuccess

    private fun cache(level: Level, tag: String, message: String): Boolean =
        runCatching { queue.enqueue(level, tag, message) }.isSuccess

    private fun tryFlush() {
        if (queue.isEmpty()) return

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
            if (forward(lvl, tg, msg)) {
                runCatching { queue.remove(f) }
                count++
            } else {
                // If we cannot send the oldest, stop to avoid busy loops
                break
            }
        }
    }
}