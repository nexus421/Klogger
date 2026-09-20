package bayern.kickner.klogger.loki

import bayern.kickner.klogger.Destination
import bayern.kickner.klogger.LambdaDestination
import bayern.kickner.klogger.LoggerDsl
import bayern.kickner.klogger.printStderr
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Buffers log entries in a [Channel] and sends them in batches to Loki.
 */
object LokiAppender {

    /** Internal log entry. [tsNs] is the nanosecond timestamp as a string (required by Loki). */
    private data class Entry(
        val tsNs: String,
        val level: String,
        val tag: String,
        val message: String,
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
    var flushInterval = 1000.milliseconds

    /** Maximum number of entries per HTTP request to Loki. */
    @Volatile
    var batchMaxSize = 50

    @Volatile
    private var channel: Channel<Entry> = Channel(500, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val lastTimestampNs = AtomicLong(0L)

    /** Arbitrary key-value pairs embedded into every log line. Can be overwritten at any time. */
    @Volatile
    var contextFields: Map<String, String> = emptyMap()

    @Volatile
    private var pushUrl: String? = null
    @Volatile
    private var streamLabels: Map<String, String> = emptyMap()
    @Volatile
    private var bearerToken = ""

    /** The Ktor HTTP client used to send requests to Loki. */
    var client: HttpClient = HttpClient()

    /** The scope currently running the flush loop. Can be used to cancel logging if needed. */
    var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private set

    @Volatile
    private var flushJob: Job? = null

    /** The one destination instance representing this appender; registered by [logToLoki]. */
    internal val destination: Destination = LambdaDestination { level, tag, message ->
        enqueue(level.name, tag, message)
    }

    /**
     * Starts the flush loop. Called by [logToLoki].
     */
    internal fun start(
        lokiBaseUrl: String,
        appName: String,
        token: String,
        maxQueueSize: Int,
        flushInterval: Duration,
        batchMaxSize: Int,
        scope: CoroutineScope,
        client: HttpClient = this.client
    ) {
        pushUrl = "${lokiBaseUrl.trimEnd('/')}/loki/api/v1/push"
        streamLabels = mapOf("app" to appName)
        bearerToken = token
        this.flushInterval = flushInterval
        this.batchMaxSize = batchMaxSize
        this.client = client
        val newChannel = Channel<Entry>(maxQueueSize, onBufferOverflow = BufferOverflow.DROP_OLDEST)
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
                runCatching { flush() }.onFailure { printStderr("LokiAppender: flush failed – $it") }
            }
        }
    }

    internal fun enqueue(level: String, tag: String, message: String) {
        val nowNs = Clock.System.now().toEpochMilliseconds() * 1_000_000L
        val tsNs = lastTimestampNs.updateAndFetch { prev: Long -> maxOf(prev + 1, nowNs) }
        channel.trySend(Entry(tsNs.toString(), level, tag, message, contextFields.toMap()))
    }

    internal fun flush() {
        val currentBatchMaxSize = batchMaxSize
        val batch = ArrayList<Entry>(currentBatchMaxSize)
        for (i in 0 until currentBatchMaxSize) {
            val entry = channel.tryReceive().getOrNull() ?: break
            batch.add(entry)
        }
        if (batch.isEmpty()) return

        val values = batch.map { entry ->
            val logLine = buildJsonObject {
                put("level", entry.level)
                put("tag", entry.tag)
                put("message", entry.message)
                entry.contextFields.forEach { (k, v) -> put(k, v) }
            }.toString()
            listOf(entry.tsNs, logLine)
        }
        val bodyString = Json.encodeToString(LokiBody(listOf(LokiStream(streamLabels, values))))
        val currentUrl = pushUrl ?: return

        runBlocking(Dispatchers.IO) {
            runCatching {
                val currentToken = bearerToken
                val response = client.post(currentUrl) {
                    header("Content-Type", "application/json")
                    if (currentToken.isNotEmpty()) {
                        header("Authorization", "Bearer $currentToken")
                    }
                    setBody(bodyString)
                }
                val responseCode = response.status.value
                if (responseCode !in 200..299) {
                    printStderr("LokiAppender: HTTP $responseCode")
                }
            }.onFailure {
                printStderr("LokiAppender: send failed – ${it.message}")
            }
        }
    }
}

/**
 * Extension on [LoggerDsl]: attaches Loki as an additional log destination inside `KLogger.configure`.
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
    client: HttpClient = LokiAppender.client
) {
    LokiAppender.contextFields = contextFields
    LokiAppender.start(lokiBaseUrl, appName, bearerToken, maxQueueSize, flushInterval, batchMaxSize, scope, client)
    logTo(LokiAppender.destination)
}
