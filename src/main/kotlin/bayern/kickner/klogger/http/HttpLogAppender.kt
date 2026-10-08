package bayern.kickner.klogger.http

import bayern.kickner.klogger.Destination
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.LoggerDsl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A single buffered log entry passed to the [HttpLogAppender.bodyBuilder].
 *
 * @param timestampNs Nanosecond timestamp, strictly monotonically increasing per appender instance.
 * @param level Log level name, e.g. `"INFO"`.
 * @param tag Logger tag (usually the simple class name).
 * @param message The log message.
 */
data class LogEntry(
    val timestampNs: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/**
 * A configurable, batch-based HTTP log appender.
 *
 * Buffers log entries in an in-memory [Channel] and periodically drains them into a single
 * HTTP request. The request body is produced by a user-supplied [bodyBuilder] lambda, so the
 * wire format (JSON, NDJSON, plain text, …) is entirely up to the caller.
 *
 * ## Basic setup
 * ```kotlin
 * val appender = HttpLogAppender(
 *     url = "https://logs.example.com/ingest",
 *     bodyBuilder = { batch -> batch.joinToString("\n") { it.message } }
 * ).apply {
 *     addContentTypeApplicationJson()
 *     addBearer("my-token")
 * }
 *
 * KLogger.configure {
 *     logToConsole()
 *     logToHttp(appender)
 * }
 * ```
 *
 * ## Lifecycle
 * The flush loop starts when the appender is passed to [logToHttp].
 * To stop: `appender.scope.cancel()`.
 * [KLogger.flush] sends everything still buffered right away, e.g. before the process exits.
 *
 * @param url Full target URL including path, e.g. `"https://logs.example.com/ingest"`.
 *            A trailing `/` is stripped automatically.
 * @param bodyBuilder Converts the current batch of [LogEntry] items into the raw request body string.
 *                    Called on the flush coroutine. It must not throw (errors are caught and logged).
 * @param maxQueueSize In-memory buffer capacity (default 500). On overflow, the oldest entry is dropped.
 * @param initialFlushInterval Interval between flush runs (default 1 000 ms). Mutable via [flushInterval].
 * @param initialBatchMaxSize Max entries per HTTP request (default 50). Mutable via [batchMaxSize].
 * @param initialScope [CoroutineScope] for the flush loop (default: app-lifetime IO scope with [SupervisorJob]).
 *                     Accessible via [scope].
 */
class HttpLogAppender(
    url: String,
    val bodyBuilder: (List<LogEntry>) -> String,
    maxQueueSize: Int = 500,
    initialFlushInterval: Duration = 1000.milliseconds,
    initialBatchMaxSize: Int = 50,
    initialScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val parsedUrl = URI(url.trimEnd('/')).toURL()

    /** The scope running the flush loop. Cancel to stop the appender. */
    val scope: CoroutineScope = initialScope

    /** HTTP method used for each request (default `"POST"`). */
    var method: String = "POST"

    /** Interval between flush runs. Can be adjusted at runtime. */
    @Volatile
    var flushInterval: Duration = initialFlushInterval

    /** Maximum number of entries per HTTP request. Can be adjusted at runtime. */
    @Volatile
    var batchMaxSize: Int = initialBatchMaxSize

    // ConcurrentHashMap so headers can be safely rotated at runtime (e.g. a refreshed bearer
    // token) while the flush loop concurrently reads them on the IO dispatcher.
    private val headers = ConcurrentHashMap<String, String>()
    private val channel = Channel<LogEntry>(maxQueueSize, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lastTimestampNs = AtomicLong(0L)

    @Volatile
    private var flushJob: Job? = null

    /**
     * Serializes sending between the flush loop and [drain], so a drain waits for a batch the loop has in
     * flight instead of racing past it (which could lose that batch on JVM exit), and batches leave in order.
     * It guards network I/O, not state: [enqueue] never takes it, so logging stays lock-free.
     */
    private val sendLock = ReentrantLock()

    /**
     * Per-destination threshold, set by [logToHttp]. `null` = everything the global minLevel lets through.
     * A field instead of a wrapping destination, so the stable [destination] instance keeps deduplicating.
     */
    @Volatile
    internal var minLevel: KLogger.Level? = null

    /** The one destination instance representing this appender. Registered by [logToHttp]. */
    internal val destination: Destination = object : Destination {
        override fun log(level: KLogger.Level, tag: String, message: String) {
            val min = minLevel
            if (min == null || level >= min) enqueue(level.name, tag, message)
        }

        override fun flush(timeout: Duration) = drain(timeout)
    }

    // ── Header helpers ────────────────────────────────────────────────────────

    /** Adds (or overwrites) a request header. */
    fun addHeader(key: String, value: String) {
        headers[key] = value
    }

    /** Sets `Content-Type: application/json`. */
    fun addContentTypeApplicationJson() = addHeader("Content-Type", "application/json")

    /** Sets `Content-Type` to an arbitrary [contentType]. */
    fun addContentType(contentType: String) = addHeader("Content-Type", contentType)

    /** Sets `Authorization: Bearer <token>`. */
    fun addBearer(token: String) = addHeader("Authorization", "Bearer $token")

    /** Sets `Authorization: Basic <base64(user:password)>`. */
    fun addBasicAuth(user: String, password: String) {
        val encoded = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        addHeader("Authorization", "Basic $encoded")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Launches the flush loop. Returns false (and does nothing) if it is already running. */
    internal fun start(): Boolean {
        if (flushJob?.isActive == true) return false
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(this@HttpLogAppender.flushInterval)
                // Anything escaping flush() would end this coroutine and silently stop all
                // forwarding for the rest of the JVM's lifetime - report it and keep looping.
                runCatching { flush() }.onFailure { System.err.println("HttpLogAppender: flush failed – $it") }
            }
        }
        return true
    }

    internal fun enqueue(level: String, tag: String, message: String) {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val tsNs = lastTimestampNs.updateAndGet { prev -> maxOf(prev + 1, nowNs) }
        channel.trySend(LogEntry(tsNs, level, tag, message))
    }

    /** Sends one batch. Called periodically by the flush loop. */
    internal fun flush() {
        sendLock.withLock { sendBatch(timeoutMs = DEFAULT_TIMEOUT_MS) }
    }

    /**
     * Sends batches until the buffer is empty or [timeout] is used up, waiting first for a batch the
     * flush loop may have in flight. Called through [KLogger.flush].
     *
     * Sends nothing once the flush loop was stopped via `scope.cancel()`, the documented way to stop sending.
     *
     * @return `true` if the buffer was emptied (or the appender is stopped), `false` if the time ran out first.
     */
    internal fun drain(timeout: Duration): Boolean {
        if (flushJob?.isActive != true) return true
        val deadline = TimeSource.Monotonic.markNow() + timeout
        if (!sendLock.tryLock(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) return false
        try {
            while (true) {
                val remainingMs = (-deadline.elapsedNow()).inWholeMilliseconds
                // Checked before use: HttpURLConnection treats a timeout of 0 as "wait forever"
                if (remainingMs <= 0) return false
                val maxEntries = batchMaxSize
                val taken = sendBatch(timeoutMs = minOf(DEFAULT_TIMEOUT_MS.toLong(), remainingMs).toInt(), maxEntries)
                // Fewer entries than requested: the buffer ran empty, everything is out
                if (taken < maxEntries) return true
                // Only possible with batchMaxSize <= 0, which never sends anything - don't spin until the deadline
                if (taken == 0) return false
            }
        } finally {
            sendLock.unlock()
        }
    }

    /**
     * Takes up to [maxEntries] entries and sends them as one request, using [timeoutMs] as connect and read
     * timeout. Must be called while holding [sendLock].
     *
     * @return the number of entries taken from the buffer (they are gone even if sending failed).
     */
    private fun sendBatch(timeoutMs: Int, maxEntries: Int = batchMaxSize): Int {
        val currentBatchMaxSize = maxEntries
        val batch = ArrayList<LogEntry>(currentBatchMaxSize)
        for (i in 0 until currentBatchMaxSize) {
            batch.add(channel.tryReceive().getOrNull() ?: break)
        }
        if (batch.isEmpty()) return 0

        // Build body before opening the connection – fail fast without wasting a socket.
        // Failures below go to stderr, never through KLogger: this appender is itself a KLogger
        // destination, so logging them would feed the error back into this very buffer.
        val bodyBytes = runCatching { bodyBuilder(batch).toByteArray(Charsets.UTF_8) }.getOrElse {
            System.err.println("HttpLogAppender: bodyBuilder failed – ${it.message}")
            return batch.size
        }

        runCatching {
            val conn = parsedUrl.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(bodyBytes) }
            val responseCode = conn.responseCode
            // Consume body to allow JVM keep-alive connection reuse
            if (responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                conn.errorStream?.use { it.readBytes() }
                System.err.println("HttpLogAppender: HTTP $responseCode")
            }
        }.onFailure {
            System.err.println("HttpLogAppender: send failed – ${it.message}")
        }
        return batch.size
    }

    private companion object {
        /** Connect and read timeout of a regular send. A drain uses less if its deadline is closer. */
        const val DEFAULT_TIMEOUT_MS = 3_000
    }
}

/**
 * Registers an [HttpLogAppender] as a log destination and starts its flush loop.
 *
 * Configure the appender (headers, method, etc.) before this call. Headers can also be updated
 * afterwards (e.g. to rotate a token) since they're backed by a thread-safe map.
 * Keep a reference to [appender] to cancel the flush loop later via `appender.scope.cancel()`.
 * Registering the same appender again does not duplicate log lines; it replaces its [minLevel].
 *
 * @param minLevel Only messages at or above this level are sent by this appender, on top of the global
 *   [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true. `null` (default) = no extra filter.
 */
fun LoggerDsl.logToHttp(appender: HttpLogAppender, minLevel: KLogger.Level? = null) {
    appender.minLevel = minLevel
    appender.start()
    logTo(appender.destination)
}
