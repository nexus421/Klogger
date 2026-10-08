package bayern.kickner.klogger

import bayern.kickner.klogger.KLogger.Level
import java.io.File
import java.io.IOException
import kotlin.time.Duration

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

    /**
     * Delivers everything this destination still buffers and should return within about [timeout];
     * [KLogger.flush] enforces the hard limit.
     *
     * Called by [KLogger.flush], never on the logging hot path. Like [log], it must not throw.
     * The default does nothing, which is correct for destinations that write synchronously.
     *
     * @return `true` if nothing is left to send (a stopped appender has nothing left to send), `false` if the time
     *   ran out first.
     */
    fun flush(timeout: Duration): Boolean = true
}

/**
 * A logging destination that writes log messages to the console.
 *
 * Log messages with levels `ERROR` and `CRASH` are written to `System.err`,
 * while other levels are written to standard output (`System.out`).
 *
 * Lines are formatted with the configured [LoggerDsl.format] (default: [KLogger.formatLogDefault]).
 */
internal class ConsoleDestination : Destination {
    override fun log(level: Level, tag: String, message: String) {
        if (level == Level.ERROR || level == Level.CRASH) System.err.println(KLogger.format(level, tag, message))
        else println(KLogger.format(level, tag, message))
    }

    /** Flushes both streams, in case `System.out`/`System.err` were replaced by buffering streams. */
    override fun flush(timeout: Duration): Boolean {
        System.out.flush()
        System.err.flush()
        return true
    }
}

/**
 * A logging destination that appends log lines, formatted with the configured [LoggerDsl.format], to [file],
 * optionally with size-based rotation.
 *
 * The file and its parent directories are created if missing; existing content is kept. Writing is
 * thread-safe via `@Synchronized`. Several instances (or processes) writing the same file are not supported.
 *
 * Rotation is off unless [maxFileSize] is set. Then, before a line would push the file beyond [maxFileSize]
 * bytes, `app.log` is renamed to `app.log.1`, `app.log.1` to `app.log.2` and so on; the oldest backup beyond
 * [maxBackupFiles] is deleted. A single line larger than [maxFileSize] is still written, into a fresh file.
 * If rotation fails (e.g. the file is locked), lines keep being appended to [file] and the failure is
 * reported once on stderr.
 *
 * @param file The file to which log messages will be written.
 * @param maxFileSize Rotate before the file would exceed this many bytes. `null` = never rotate. Must be > 0.
 * @param maxBackupFiles Number of rotated files kept next to [file]. Must be >= 1.
 */
internal class FileDestination(
    private val file: File,
    private val maxFileSize: Long? = null,
    private val maxBackupFiles: Int = 3,
) : Destination {
    /** Bytes written since the last rotation attempt; starts at the size of an existing file. */
    private var writtenBytes: Long
    private var rotationFailureReported = false

    init {
        require(maxFileSize == null || maxFileSize > 0) { "maxFileSize must be > 0, was $maxFileSize" }
        require(maxBackupFiles >= 1) { "maxBackupFiles must be >= 1, was $maxBackupFiles" }
        file.parentFile?.mkdirs()
        if (file.exists().not()) file.createNewFile()
        writtenBytes = file.length()
    }

    @Synchronized
    override fun log(level: Level, tag: String, message: String) {
        val bytes = (KLogger.format(level, tag, message) + System.lineSeparator()).toByteArray(Charsets.UTF_8)
        // writtenBytes > 0: a single line larger than maxFileSize is still written, into a fresh file
        if (maxFileSize != null && writtenBytes > 0 && writtenBytes + bytes.size > maxFileSize) rotate()
        file.appendBytes(bytes)
        writtenBytes += bytes.size
    }

    /** app.log -> app.log.1 -> app.log.2 ... up to [maxBackupFiles]; the oldest backup is deleted. */
    private fun rotate() {
        val rotated = runCatching {
            // Only the existing backups app.log.1..n are touched (they are contiguous, the scan stops at the first
            // gap), so the cost grows with the backups on disk, never with a large maxBackupFiles.
            var existing = 0
            while (existing < maxBackupFiles && backup(existing + 1).exists()) existing++
            // Stop at the first failed step: carrying on would rename a file onto a backup that could not
            // be moved away, which replaces that backup and silently loses its lines.
            if (existing == maxBackupFiles) {
                if (!backup(existing).delete()) return@runCatching false
                existing--
            }
            for (i in existing downTo 1) {
                if (!backup(i).renameTo(backup(i + 1))) return@runCatching false
            }
            file.renameTo(backup(1))
        }.getOrDefault(false)
        // On failure keep appending to the current file and retry after another maxFileSize bytes instead of
        // on every single line. Reported on stderr, never through KLogger: we are a destination ourselves.
        if (!rotated && !rotationFailureReported) {
            System.err.println("FileDestination: could not rotate $file, continuing without rotation")
            rotationFailureReported = true
        }
        writtenBytes = 0
    }

    private fun backup(index: Int) = File(file.path + "." + index)
}

/**
 * Passes only messages at or above [minLevel] on to [delegate]; used for the `minLevel` parameter of the
 * `logTo*` functions. Applied after the global [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true.
 */
internal class LevelFilterDestination(private val minLevel: Level, private val delegate: Destination) : Destination {
    override fun log(level: Level, tag: String, message: String) {
        if (level >= minLevel) delegate.log(level, tag, message)
    }

    override fun flush(timeout: Duration): Boolean = delegate.flush(timeout)
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

    /** True if no queue entry exists. Only `.logq` files count, never `.tmp` or unrelated files. */
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