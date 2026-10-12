package bayern.kickner.klogger.loki

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.LoggerDsl
import bayern.kickner.klogger.TestHttpServer
import bayern.kickner.klogger.infoLog
import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.MDC
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class LokiMdcTest {

    private lateinit var server: TestHttpServer
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        MDC.clear()
        KLogger.resetForTest()
        server = TestHttpServer().apply { status = 204 }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        MDC.clear()
        scope.cancel()
        server.close()
        LokiAppender.contextFields = emptyMap()
        KLogger.resetForTest()
    }

    private fun configureLoki(
        contextFields: Map<String, String> = emptyMap(),
        maxQueueSize: Int = 500,
        extra: LoggerDsl.() -> Unit = {},
    ) = KLogger.configure {
        logToLoki(
            lokiBaseUrl = server.url, appName = "test", bearerToken = "t", contextFields = contextFields,
            maxQueueSize = maxQueueSize, flushInterval = 1.hours, scope = scope,
        )
        extra()
    }

    /** Raw JSON log lines (`streams[0].values[i][1]`) of all requests, in order. */
    private fun rawLines(): List<String> = server.bodies.flatMap { body ->
        val streams = Json.parseToJsonElement(body).jsonObject.getValue("streams").jsonArray
        assertEquals(1, streams.size)
        streams[0].jsonObject.getValue("values").jsonArray.map { it.jsonArray[1].jsonPrimitive.content }
    }

    private fun lines(): List<Map<String, String>> = rawLines().map { line ->
        Json.parseToJsonElement(line).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `MDC becomes separate JSON fields and wins over contextFields`() {
        configureLoki(contextFields = mapOf("app_version" to "1", "system" to "global"))
        MDC.put("correlationId", "C1")
        MDC.put("system", "checkout")

        infoLog { "loadOrder → 200" }
        assertTrue(KLogger.flush())

        val line = lines().single()
        assertEquals(
            mapOf(
                "level" to "INFO", "tag" to "LokiMdcTest", "message" to "loadOrder → 200",
                "app_version" to "1", "system" to "checkout", "correlationId" to "C1",
            ),
            line,
        )
        assertEquals(listOf("level", "tag", "message"), line.keys.take(3))
    }

    @Test
    fun `level, tag and message cannot be overwritten`() {
        configureLoki(contextFields = mapOf("message" to "EVIL2", "tag" to "EVIL2"))
        MDC.put("message", "EVIL")
        MDC.put("level", "X")
        MDC.put("tag", "Y")
        MDC.put("ok", "1")

        KLogger.warn("RealTag") { "real" }
        assertTrue(KLogger.flush())

        assertEquals(mapOf("level" to "WARN", "tag" to "RealTag", "message" to "real", "ok" to "1"), lines().single())
    }

    @Test
    fun `without MDC the line is unchanged`() {
        configureLoki()

        KLogger.info("T") { "hallo" }
        LokiAppender.contextFields = linkedMapOf("instance" to "web-1", "version" to "1.4.0")
        KLogger.info("T") { "hallo" }
        assertTrue(KLogger.flush())

        assertEquals(
            listOf(
                """{"level":"INFO","tag":"T","message":"hallo"}""",
                """{"level":"INFO","tag":"T","message":"hallo","instance":"web-1","version":"1.4.0"}""",
            ),
            rawLines(),
        )
    }

    @Test
    fun `text destinations get the suffix, Loki gets fields`() {
        val custom = mutableListOf<String>()
        configureLoki { logToCustom { _, _, message -> custom += message } }
        MDC.put("correlationId", "C1")

        KLogger.info("T") { "hallo" }
        assertTrue(KLogger.flush())

        assertEquals(listOf("hallo {correlationId=C1}"), custom)
        assertEquals(
            mapOf("level" to "INFO", "tag" to "T", "message" to "hallo", "correlationId" to "C1"),
            lines().single()
        )
    }

    @Test
    fun `includeMdc false keeps the MDC out of Loki`() {
        configureLoki { includeMdc = false }
        MDC.put("correlationId", "C1")

        KLogger.info("T") { "hallo" }
        assertTrue(KLogger.flush())

        assertEquals(mapOf("level" to "INFO", "tag" to "T", "message" to "hallo"), lines().single())
    }

    @Test
    fun `direct calls without context still work`() {
        configureLoki()
        MDC.put("correlationId", "C1")

        LokiAppender.destination.log(KLogger.Level.INFO, "T", "direct")
        LokiAppender.enqueue("INFO", "T", "enqueued")
        assertTrue(KLogger.flush())

        assertEquals(listOf("direct", "enqueued"), lines().map { it.getValue("message") })
        assertTrue(lines().none { "correlationId" in it })
    }

    @Test
    fun `coroutines with MDCContext get their own correlationId in Loki`() {
        configureLoki(maxQueueSize = 1000)

        runBlocking {
            (0 until 200).map { i ->
                launch(Dispatchers.Default + MDCContext(mapOf("correlationId" to "ID-$i"))) {
                    repeat(3) {
                        delay(Random.nextLong(0, 4))
                        withContext(Dispatchers.IO) { yield() }
                        KLogger.info("T") { "msg ID-$i" }
                    }
                }
            }.joinAll()
        }
        assertTrue(KLogger.flush(10.seconds))

        val lines = lines()
        assertEquals(600, lines.size)
        val mismatches = lines.filter { it["correlationId"] != it.getValue("message").removePrefix("msg ") }
        assertEquals(emptyList(), mismatches)
        assertEquals(
            (0 until 200).associate { "ID-$it" to 3 },
            lines.groupingBy { it.getValue("correlationId") }.eachCount()
        )
    }
}
