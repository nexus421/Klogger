package bayern.kickner.klogger.http

import bayern.kickner.klogger.Destination
import bayern.kickner.klogger.LambdaDestination
import bayern.kickner.klogger.LoggerDsl
import bayern.kickner.klogger.printStderr
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock

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
 */
class HttpLogAppender(
    url: String,
    val bodyBuilder: (List<LogEntry>) -> String,
    maxQueueSize: Int = 500,
    initialFlushInterval: Duration = 1000.milliseconds,
    initialBatchMaxSize: Int = 50,
    initialScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val client: HttpClient = HttpClient(),
) {
    val targetUrl = url.trimEnd('/')

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

    private val headersRef = AtomicReference<Map<String, String>>(emptyMap())
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
        while (true) {
            val current = headersRef.load()
            if (headersRef.compareAndSet(current, current + (key to value))) break
        }
    }

    /** Sets `Content-Type: application/json`. */
    fun addContentTypeApplicationJson() = addHeader("Content-Type", "application/json")

    /** Sets `Content-Type` to an arbitrary [contentType]. */
    fun addContentType(contentType: String) = addHeader("Content-Type", contentType)

    /** Sets `Authorization: Bearer <token>`. */
    fun addBearer(token: String) = addHeader("Authorization", "Bearer $token")

    /** Sets `Authorization: Basic <base64(user:password)>`. */
    fun addBasicAuth(user: String, password: String) {
        val encoded = "$user:$password".encodeBase64()
        addHeader("Authorization", "Basic $encoded")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Launches the flush loop; returns false (and does nothing) if it is already running. */
    internal fun start(): Boolean {
        if (flushJob?.isActive == true) return false
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(this@HttpLogAppender.flushInterval)
                runCatching { flush() }.onFailure { printStderr("HttpLogAppender: flush failed – $it") }
            }
        }
        return true
    }

    internal fun enqueue(level: String, tag: String, message: String) {
        val nowNs = Clock.System.now().toEpochMilliseconds() * 1_000_000L
        val tsNs = lastTimestampNs.updateAndFetch { prev: Long -> maxOf(prev + 1, nowNs) }
        channel.trySend(LogEntry(tsNs, level, tag, message))
    }

    internal fun flush() {
        val currentBatchMaxSize = batchMaxSize
        val batch = ArrayList<LogEntry>(currentBatchMaxSize)
        for (i in 0 until currentBatchMaxSize) {
            batch.add(channel.tryReceive().getOrNull() ?: break)
        }
        if (batch.isEmpty()) return

        val bodyString = runCatching { bodyBuilder(batch) }.getOrElse {
            printStderr("HttpLogAppender: bodyBuilder failed – ${it.message}")
            return
        }

        runBlocking(Dispatchers.IO) {
            runCatching {
                val currentHeaders = headersRef.load()
                val response = client.request(targetUrl) {
                    this.method = HttpMethod.parse(this@HttpLogAppender.method)
                    currentHeaders.forEach { (k, v) ->
                        header(k, v)
                    }
                    setBody(bodyString)
                }
                val responseCode = response.status.value
                if (responseCode !in 200..299) {
                    printStderr("HttpLogAppender: HTTP $responseCode")
                }
            }.onFailure {
                printStderr("HttpLogAppender: send failed – ${it.message}")
            }
        }
    }
}

/**
 * Registers an [HttpLogAppender] as a log destination and starts its flush loop.
 */
fun LoggerDsl.logToHttp(appender: HttpLogAppender) {
    appender.start()
    logTo(appender.destination)
}
