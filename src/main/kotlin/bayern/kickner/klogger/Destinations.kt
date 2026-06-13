package bayern.kickner.klogger

import bayern.kickner.klogger.KLogger.Level
import bayern.kickner.klogger.KLogger.formatLogDefault
import java.io.File

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
internal class FileBackedLogQueue(private val dir: File) {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    init {
        dir.mkdirs()
    }

    @Synchronized
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