package bayern.kickner.klogger.http

import bayern.kickner.klogger.Destination
import bayern.kickner.klogger.LambdaDestination
import bayern.kickner.klogger.LoggerDsl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A single buffered log entry passed to the [HttpLogAppender.bodyBuilder].
 *
 * @param timestampNs Nanosecond timestamp; strictly monotonically increasing per appender instance.
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
 *
 * @param url Full target URL including path, e.g. `"https://logs.example.com/ingest"`.
 *            A trailing `/` is stripped automatically.
 * @param bodyBuilder Converts the current batch of [LogEntry] items into the raw request body string.
 *                    Called on the flush coroutine; must not throw (errors are caught and logged).
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

    /** The one destination instance representing this appender; registered by [logToHttp]. */
    internal val destination: Destination = LambdaDestination { level, tag, message ->
        enqueue(level.name, tag, message)
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

    /** Launches the flush loop; returns false (and does nothing) if it is already running. */
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

    internal fun flush() {
        val currentBatchMaxSize = batchMaxSize
        val batch = ArrayList<LogEntry>(currentBatchMaxSize)
        for (i in 0 until currentBatchMaxSize) {
            batch.add(channel.tryReceive().getOrNull() ?: break)
        }
        if (batch.isEmpty()) return

        // Build body before opening the connection – fail fast without wasting a socket.
        // Failures below go to stderr, never through KLogger: this appender is itself a KLogger
        // destination, so logging them would feed the error back into this very buffer.
        val bodyBytes = runCatching { bodyBuilder(batch).toByteArray(Charsets.UTF_8) }.getOrElse {
            System.err.println("HttpLogAppender: bodyBuilder failed – ${it.message}")
            return
        }

        runCatching {
            val conn = parsedUrl.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.doOutput = true
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
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
    }
}

/**
 * Registers an [HttpLogAppender] as a log destination and starts its flush loop.
 *
 * Configure the appender (headers, method, etc.) before this call. Headers can also be updated
 * afterwards (e.g. to rotate a token) since they're backed by a thread-safe map.
 * Keep a reference to [appender] to cancel the flush loop later via `appender.scope.cancel()`.
 */
fun LoggerDsl.logToHttp(appender: HttpLogAppender) {
    appender.start()
    logTo(appender.destination)
}
