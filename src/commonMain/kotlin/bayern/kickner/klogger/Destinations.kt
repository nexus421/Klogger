package bayern.kickner.klogger

import bayern.kickner.klogger.KLogger.Level
import bayern.kickner.klogger.KLogger.formatLogDefault
import kotlin.concurrent.atomics.*
import kotlinx.datetime.Clock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

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
 * Log messages with levels `ERROR` and `CRASH` are written to standard error,
 * while other levels are written to standard output.
 *
 * This class delegates message formatting to the `formatLogDefault` function.
 */
internal class ConsoleDestination : Destination {
    override fun log(level: Level, tag: String, message: String) {
        val formatted = formatLogDefault(level, tag, message)
        if (level == Level.ERROR || level == Level.CRASH) printStderr(formatted)
        else println(formatted)
    }
}

/**
 * A logging destination that writes log messages to a specified file.
 *
 * This class is responsible for outputting log messages to a file. The file
 * and its parent directories are created if they do not already exist.
 * Log messages are appended to the file in a thread-safe manner.
 *
 * @param pathString The file path to which log messages will be written.
 */
internal class FileDestination(private val pathString: String) : Destination {
    private val path = Path(pathString)
    private val lock = Lock()

    init {
        path.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        if (!SystemFileSystem.exists(path)) {
            SystemFileSystem.sink(path).close()
        }
    }

    override fun log(level: Level, tag: String, message: String) {
        lock.withLock {
            val line = formatLogDefault(level, tag, message) + "\n"
            SystemFileSystem.sink(path, append = true).buffered().use { sink ->
                sink.writeString(line)
            }
        }
    }
}

/**
 * A logging destination that executes a lambda whenever a log message is dispatched.
 */
internal class LambdaDestination(private val block: (level: Level, tag: String, message: String) -> Unit) :
    Destination {
    override fun log(level: Level, tag: String, message: String) = block(level, tag, message)
}

/**
 * A thread-safe, file-backed queue for storing log messages. Each log message is stored in its
 * own file within the specified directory. The files are named based on the current timestamp
 * and a counter to ensure uniqueness.
 */
internal class FileBackedLogQueue(dirPath: String, private val maxQueueSize: Int = 500) {
    internal val dir = Path(dirPath)
    private val counter = AtomicLong(0)
    private val lock = Lock()

    init {
        require(maxQueueSize >= 1) { "maxQueueSize must be >= 1, was $maxQueueSize" }
        if (!SystemFileSystem.exists(dir)) {
            SystemFileSystem.createDirectories(dir)
        }
    }

    /** True if no queue entry exists. Only `.logq` files count - never `.tmp` or unrelated files. */
    fun isEmpty(): Boolean = lock.withLock {
        listFiles().isEmpty()
    }

    fun enqueue(level: Level, tag: String, message: String) = lock.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        val name = "${now}_${counter.fetchAndIncrement().toString().padStart(19, '0')}.logq"
        val file = Path(dir, name)
        val tmp = Path(dir, "$name.tmp")
        val singleLineTag = tag.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        try {
            SystemFileSystem.sink(tmp).buffered().use { sink ->
                sink.writeString(level.name + "\n" + singleLineTag + "\n" + message)
            }
            SystemFileSystem.atomicMove(tmp, file)
        } catch (e: Throwable) {
            runCatching { SystemFileSystem.delete(tmp) }
            throw e
        }
        trimToMaxSize()
    }

    /** Drops the oldest cached entries beyond [maxQueueSize] so the cache can't grow unbounded. */
    private fun trimToMaxSize() {
        val files = listFiles()
        val excess = files.size - maxQueueSize
        if (excess <= 0) return
        files.take(excess).forEach { runCatching { SystemFileSystem.delete(it) } }
    }

    fun listFiles(): List<Path> = lock.withLock {
        if (!SystemFileSystem.exists(dir)) return emptyList()
        runCatching { SystemFileSystem.list(dir) }.getOrNull()
            ?.filter { it.name.endsWith(".logq") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun read(file: Path): Triple<Level, String, String> = lock.withLock {
        val content = SystemFileSystem.source(file).buffered().use { it.readString() }
        val lines = content.split('\n')
        val lvl = Level.valueOf(lines.first())
        val tag = if (lines.size >= 2) lines[1] else ""
        val msg = if (lines.size >= 3) lines.drop(2).joinToString("\n") else ""
        Triple(lvl, tag, msg)
    }

    fun remove(file: Path) = lock.withLock {
        SystemFileSystem.delete(file)
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
    private val lock = Lock()

    init {
        require(maxFlushPerCall >= 1) { "maxFlushPerCall must be >= 1, was $maxFlushPerCall" }
    }

    override fun log(level: Level, tag: String, message: String) {
        lock.withLock {
            tryFlush()
            val delivered = if (queue.isEmpty()) {
                forward(level, tag, message) || cache(level, tag, message)
            } else {
                cache(level, tag, message) || forward(level, tag, message)
            }
            if (!delivered) {
                printStderr("CachedForwardingDestination: dropped $level log '$tag' - target and cache both unavailable")
            }
        }
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
                runCatching { queue.remove(f) }
                continue
            }
            if (forward(lvl, tg, msg)) {
                runCatching { queue.remove(f) }
                count++
            } else {
                break
            }
        }
    }
}
