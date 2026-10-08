package bayern.kickner.klogger.loki

import bayern.kickner.klogger.Destination
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.LoggerDsl
import bayern.kickner.klogger.loki.LokiAppender.batchMaxSize
import bayern.kickner.klogger.loki.LokiAppender.flushInterval
import bayern.kickner.klogger.loki.LokiAppender.lastTimestampNs
import bayern.kickner.klogger.loki.LokiAppender.sendLock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Buffers log entries in a [Channel] and sends them in batches to Loki.
 *
 * ## How it works
 * Each log call writes non-blocking into the channel via [Channel.trySend].
 * When the buffer is full, [BufferOverflow.DROP_OLDEST] kicks in automatically.
 * A background coroutine flushes the channel periodically and sends up to
 * [batchMaxSize] entries as a single HTTP POST to Loki.
 * On Loki failure, errors are reported on stderr and the application continues unaffected.
 * [KLogger.flush] sends everything still buffered right away, e.g. before the process exits.
 *
 * ## Configuration
 * All parameters ([maxQueueSize], [flushInterval], [batchMaxSize]) are set via [logToLoki]
 * and applied on each [start] call. The channel is recreated on every [start] call so that
 * the chosen buffer size takes effect.
 *
 * ## Timestamp collisions
 * If two log entries arrive within the same millisecond, they would receive the same
 * nanosecond timestamp (Loki requires uniqueness per stream). [lastTimestampNs] is therefore
 * an [AtomicLong] that is always set to `max(now, last + 1)`. Timestamps are therefore strictly
 * monotonically increasing and collision-free.
 *
 * ## Dynamic context
 * [contextFields] holds arbitrary key-value pairs that are embedded into every log line.
 * Filterable in Loki via `{app="myapp"} | json | callId = "..."`.
 * Simply overwrite at any time: `LokiAppender.contextFields = mapOf("call-id" to id, "tenant" to name)`
 *
 * ## Setup
 * Single-line setup via the [logToLoki] extension inside `KLogger.configure`:
 * ```kotlin
 * KLogger.configure {
 *     logToConsole()
 *     logToLoki(
 *         lokiBaseUrl = "http://loki:3100",
 *         appName = "my-app",
 *         bearerToken = "secret"
 *     )
 * }
 * ```
 *
 * ## Loki data format
 * Each batch is sent as a single HTTP POST to `/loki/api/v1/push`.
 * The `app` label identifies the application in Loki.
 * Log lines are JSON and always contain `level`, `tag`, `message`, plus all context fields.
 * Example: `{"level":"INFO","tag":"Handler","message":"...","callId":"0049123456","tenant":"Foo"}`
 */
object LokiAppender {

    /** Internal log entry. [tsNs] is the nanosecond timestamp as a string (required by Loki). */
    private data class Entry(
        val tsNs: String,
        val level: String,
        val tag: String,
        val message: String,
        /** Snapshot of the context fields at the time of the log call. */
        val contextFields: Map<String, String>
    )

    /** Loki stream: fixed label set + list of log values. */
    @Serializable
    private data class LokiStream(val stream: Map<String, String>, val values: List<List<String>>)

    /** Outer wrapper of the Loki push body. */
    @Serializable
    private data class LokiBody(val streams: List<LokiStream>)

    /** Interval between two automatic flush runs. */
    @Volatile
    private var flushInterval = 1000.milliseconds

    /** Maximum number of entries per HTTP request to Loki. */
    @Volatile
    private var batchMaxSize = 50

    /**
     * Buffer between log calls and the flush loop.
     * Recreated on each [start] call with the chosen queue size.
     * [BufferOverflow.DROP_OLDEST] ensures that when the buffer is full,
     * the oldest entry (not the newest) is silently discarded.
     */
    @Volatile
    private var channel: Channel<Entry> = Channel(500, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Last assigned nanosecond timestamp.
     * Ensures timestamps are strictly monotonically increasing even when
     * multiple log entries arrive within the same millisecond.
     */
    private val lastTimestampNs = AtomicLong(0L)

    /** Arbitrary key-value pairs embedded into every log line. Can be overwritten at any time. */
    @Volatile
    var contextFields: Map<String, String> = emptyMap()

    @Volatile
    private var pushUrl: URL? = null
    @Volatile
    private var streamLabels: Map<String, String> = emptyMap()
    @Volatile
    private var bearerToken = ""

    /** The scope currently running the flush loop. Can be used to cancel logging if needed. */
    var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private set

    @Volatile
    private var flushJob: Job? = null

    /**
     * Serializes sending between the flush loop and [drain], so a drain waits for a batch the loop has in
     * flight instead of racing past it (which could lose that batch on JVM exit), and batches leave in order.
     * It guards network I/O, not state: [enqueue] never takes it, so logging stays lock-free.
     */
    private val sendLock = ReentrantLock()

    /**
     * Per-destination threshold, set by [logToLoki]. `null` = everything the global minLevel lets through.
     * A field instead of a wrapping destination, so the stable [destination] instance keeps deduplicating.
     */
    @Volatile
    private var minLevel: KLogger.Level? = null

    /** The one destination instance representing this appender. Registered by [logToLoki]. */
    internal val destination: Destination = object : Destination {
        override fun log(level: KLogger.Level, tag: String, message: String) {
            val min = minLevel
            if (min == null || level >= min) enqueue(level.name, tag, message)
        }

        override fun flush(timeout: Duration) = drain(timeout)
    }

    /**
     * Starts the flush loop. Called by [logToLoki].
     *
     * Recreates the internal [Channel] so that [maxQueueSize] takes effect.
     *
     * @param lokiBaseUrl Base URL without path, e.g. `"http://loki:3100"`
     * @param appName Value of the `app` label in Loki
     * @param token Bearer token for the `Authorization: Bearer` header
     * @param maxQueueSize Maximum buffer size. The oldest entry is dropped on overflow.
     * @param flushInterval Interval between two flush runs
     * @param batchMaxSize Maximum number of entries per HTTP request
     * @param scope Scope for the flush loop (blocking HTTP calls run on [Dispatchers.IO])
     * @param minLevel Per-destination threshold, `null` = no extra filter
     */
    internal fun start(
        lokiBaseUrl: String,
        appName: String,
        token: String,
        maxQueueSize: Int,
        flushInterval: Duration,
        batchMaxSize: Int,
        scope: CoroutineScope,
        minLevel: KLogger.Level? = null,
    ) {
        this.minLevel = minLevel
        pushUrl = URI("${lokiBaseUrl.trimEnd('/')}/loki/api/v1/push").toURL()
        streamLabels = mapOf("app" to appName)
        bearerToken = token
        this.flushInterval = flushInterval
        this.batchMaxSize = batchMaxSize
        val newChannel = Channel<Entry>(maxQueueSize, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        // Carry over any entries still buffered in the old channel so that reconfiguring
        // (calling logToLoki again) doesn't silently drop unflushed log lines.
        val oldChannel = channel
        while (true) {
            val entry = oldChannel.tryReceive().getOrNull() ?: break
            newChannel.trySend(entry)
        }
        channel = newChannel
        flushJob?.cancel()
        this.scope = scope
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(this@LokiAppender.flushInterval)
                // Anything escaping flush() would end this coroutine and silently stop all
                // forwarding for the rest of the JVM's lifetime - report it and keep looping.
                runCatching { flush() }.onFailure { System.err.println("LokiAppender: flush failed – $it") }
            }
        }
    }

    /**
     * Writes a log entry non-blocking into the channel.
     * Captures the current context as a snapshot in the entry.
     * When the buffer is full, [BufferOverflow.DROP_OLDEST] kicks in automatically.
     */
    internal fun enqueue(level: String, tag: String, message: String) {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        // updateAndGet guarantees atomic collision avoidance: always >= nowNs and > last value
        val tsNs = lastTimestampNs.updateAndGet { prev -> maxOf(prev + 1, nowNs) }
        // toMap() copies: a caller-owned MutableMap must not leak later mutations into this entry
        channel.trySend(Entry(tsNs.toString(), level, tag, message, contextFields.toMap()))
    }

    /**
     * Drains up to [batchMaxSize] entries from the channel and sends them as a single HTTP request.
     * Each log line is serialized as JSON including all context fields.
     * Errors are reported on stderr, never thrown.
     */
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

    /** Connect and read timeout of a regular send. A drain uses less if its deadline is closer. */
    private const val DEFAULT_TIMEOUT_MS = 3000

    /**
     * Takes up to [maxEntries] entries and sends them as one request, using [timeoutMs] as connect and read
     * timeout. Must be called while holding [sendLock].
     *
     * @return the number of entries taken from the buffer (they are gone even if sending failed).
     */
    private fun sendBatch(timeoutMs: Int, maxEntries: Int = batchMaxSize): Int {
        val currentBatchMaxSize = maxEntries
        val batch = ArrayList<Entry>(currentBatchMaxSize)
        for (i in 0 until currentBatchMaxSize) {
            val entry = channel.tryReceive().getOrNull() ?: break
            batch.add(entry)
        }
        if (batch.isEmpty()) return 0

        // Loki format: values = list of [timestamp_ns, log_line_as_json]
        val values = batch.map { entry ->
            val logLine = buildJsonObject {
                put("level", entry.level)
                put("tag", entry.tag)
                put("message", entry.message)
                entry.contextFields.forEach { (k, v) -> put(k, v) }
            }.toString()
            listOf(entry.tsNs, logLine)
        }
        // Serialize and encode before opening the connection – fail fast without wasting a socket
        val bodyBytes =
            Json.encodeToString(LokiBody(listOf(LokiStream(streamLabels, values)))).toByteArray(Charsets.UTF_8)
        val currentUrl = pushUrl ?: return batch.size

        runCatching {
            val conn = currentUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.outputStream.use { it.write(bodyBytes) }
            val responseCode = conn.responseCode
            // Consume the response body so the JVM can reuse the TCP connection (HTTP keep-alive)
            if (responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                conn.errorStream?.use { it.readBytes() }
                // stderr, never KLogger: this appender is itself a KLogger destination, logging the
                // failure would feed it straight back into the Loki buffer.
                System.err.println("LokiAppender: HTTP $responseCode")
            }
            // No disconnect() – allows TCP connection reuse via JVM keep-alive pool
        }.onFailure {
            // Loki unreachable → drop the batch, application keeps running
            System.err.println("LokiAppender: send failed – ${it.message}")
        }
        return batch.size
    }
}

/**
 * Extension on [LoggerDsl]: attaches Loki as an additional log destination inside `KLogger.configure`.
 *
 * Logs are not sent immediately but buffered and periodically transmitted as a batch.
 * See [LokiAppender] for details on buffering strategy, timestamp handling, and context fields.
 *
 * @param lokiBaseUrl Base URL of the Loki server, e.g. `"http://loki:3100"`
 * @param appName Label value for the `"app"` key in Loki
 * @param bearerToken Bearer token for the `Authorization: Bearer` header (e.g. Grafana Cloud)
 * @param contextFields Arbitrary key-value pairs embedded into every log line
 * @param maxQueueSize Maximum buffer size. The oldest entry is dropped on overflow (default: 500).
 * @param flushInterval Interval between two flush runs (default: 1000 ms)
 * @param batchMaxSize Maximum number of entries per HTTP request to Loki (default: 50)
 * @param scope CoroutineScope for the flush loop. Defaults to an internal app-lifetime scope with
 *             a [SupervisorJob]. Pass a custom scope if you need explicit lifecycle control.
 *             The active scope is stored in [LokiAppender.scope] and can be cancelled if needed.
 *             Calling [logToLoki] again cancels the previous flush loop and replaces the scope.
 * @param minLevel Only messages at or above this level are sent to Loki, on top of the global
 *             [LoggerDsl.minLevel] and also when [LoggerDsl.debug] is true. `null` (default) = no extra filter.
 *             Like all other settings here, calling [logToLoki] again replaces it.
 */
fun LoggerDsl.logToLoki(
    lokiBaseUrl: String,
    appName: String,
    bearerToken: String,
    contextFields: Map<String, String> = emptyMap(),
    maxQueueSize: Int = 500,
    flushInterval: Duration = 1000.milliseconds,
    batchMaxSize: Int = 50,
    scope: CoroutineScope = LokiAppender.scope,
    minLevel: KLogger.Level? = null,
) {
    LokiAppender.contextFields = contextFields
    LokiAppender.start(lokiBaseUrl, appName, bearerToken, maxQueueSize, flushInterval, batchMaxSize, scope, minLevel)
    logTo(LokiAppender.destination)
}
