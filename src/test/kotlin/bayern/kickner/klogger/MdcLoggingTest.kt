package bayern.kickner.klogger

import bayern.kickner.klogger.KLogger.Level
import bayern.kickner.klogger.http.HttpLogAppender
import bayern.kickner.klogger.http.logToHttp
import bayern.kickner.klogger.slf4j.slf4jBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class MdcLoggingTest {

    private data class Line(val level: Level, val tag: String, val message: String)

    private val captured = ConcurrentLinkedQueue<Line>()
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        MDC.clear()
        KLogger.resetForTest()
        dir = Files.createTempDirectory("klogger-mdc").toFile()
    }

    @AfterTest
    fun tearDown() {
        MDC.clear()
        KLogger.resetForTest()
        dir.deleteRecursively()
    }

    private fun captureCustom(extra: LoggerDsl.() -> Unit = {}) = KLogger.configure {
        logToCustom { level, tag, message -> captured += Line(level, tag, message) }
        extra()
    }

    @Test
    fun `own log carries the MDC as sorted suffix`() {
        captureCustom()
        MDC.put("system", "S")
        MDC.put("correlationId", "X")

        KLogger.info("Tag") { "hallo" }

        assertEquals(listOf(Line(Level.INFO, "Tag", "hallo {correlationId=X, system=S}")), captured.toList())
    }

    @Test
    fun `own log without MDC is unchanged`() {
        captureCustom()

        KLogger.info("Tag") { "hallo" }
        MDC.put("k", "v")
        MDC.remove("k") // empty but existing MDC map
        KLogger.info("Tag") { "hallo" }

        assertEquals(listOf("hallo", "hallo"), captured.map { it.message })
    }

    @Test
    fun `console line contains the MDC suffix`() {
        KLogger.configure {
            format { l, t, m -> "$l|$t|$m" }
            logToConsole()
        }
        MDC.put("correlationId", "X")

        val stdout = captureStdout { KLogger.info("Tag") { "hallo" } }

        assertEquals("INFO|Tag|hallo {correlationId=X}", stdout.trim())
    }

    @Test
    fun `file, cached forwarding and HTTP destinations get the MDC suffix`() {
        val file = File(dir, "app.log")
        val forwarded = ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        TestHttpServer().use { server ->
            val appender = HttpLogAppender(
                url = server.url,
                bodyBuilder = { batch -> batch.joinToString("\n") { it.message } },
                initialFlushInterval = 1.hours,
                initialScope = scope,
            )
            try {
                KLogger.configure {
                    format { _, _, m -> m }
                    logToFile(file)
                    logToCachedForwarding(File(dir, "cache").path, { _, _, m -> forwarded += m })
                    logToHttp(appender)
                }
                MDC.put("correlationId", "X")

                KLogger.info("Tag") { "hallo" }
                assertTrue(KLogger.flush())

                assertEquals("hallo {correlationId=X}", file.readText().trimEnd())
                assertEquals(listOf("hallo {correlationId=X}"), forwarded.toList())
                assertEquals(listOf("hallo {correlationId=X}"), server.bodies.toList())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `suffix is placed before the stack trace`() {
        captureCustom()
        MDC.put("correlationId", "X")
        val ex = IllegalStateException("boom")

        errorLog("fehler", ex)

        assertEquals("fehler {correlationId=X}\n" + ex.stackTraceToString(), captured.single().message)
    }

    @Test
    fun `includeMdc false drops the MDC from own and SLF4J lines`() {
        captureCustom {
            includeMdc = false
            slf4jBridge()
        }
        MDC.put("correlationId", "X")

        KLogger.info("Tag") { "eigen" }
        LoggerFactory.getLogger("io.ktor.x.Foo").info("bridge")

        assertEquals(listOf("eigen", "bridge"), captured.map { it.message })
    }

    @Test
    fun `includeMdc defaults to true and survives later configure calls`() {
        var seen: Boolean? = null
        KLogger.configure { seen = includeMdc }
        assertEquals(true, seen)

        KLogger.configure { includeMdc = false }
        captureCustom { seen = includeMdc }
        MDC.put("correlationId", "X")
        KLogger.info("Tag") { "hallo" }

        assertEquals(false, seen)
        assertEquals(listOf("hallo"), captured.map { it.message })
    }

    @Test
    fun `SLF4J line carries the MDC exactly once`() {
        captureCustom { slf4jBridge() }
        MDC.put("correlationId", "Z1")

        LoggerFactory.getLogger("io.ktor.x.Foo").info("hallo {}", "welt")

        assertEquals(listOf(Line(Level.INFO, "i.k.x.Foo", "hallo welt {correlationId=Z1}")), captured.toList())
    }

    @Test
    fun `SLF4J line with exception has the suffix before the stack trace`() {
        captureCustom { slf4jBridge() }
        MDC.put("correlationId", "Z1")

        LoggerFactory.getLogger("io.ktor.x.Foo").error("fehler", IllegalStateException("boom"))

        val message = captured.single().message
        assertTrue(message.startsWith("fehler {correlationId=Z1}\njava.lang.IllegalStateException: boom"), message)
        assertEquals(1, Regex("correlationId").findAll(message).count(), message)
    }

    @Test
    fun `coroutines with MDCContext never see another coroutine's MDC`() {
        captureCustom()

        runBlocking {
            (0 until 500).map { i ->
                launch(Dispatchers.Default + MDCContext(mapOf("correlationId" to "ID-$i"))) {
                    repeat(3) {
                        delay(Random.nextLong(0, 4))
                        withContext(Dispatchers.IO) { yield() }
                        KLogger.info("T") { "msg ID-$i" }
                    }
                }
            }.joinAll()
        }

        assertEquals(1500, captured.size)
        val line = Regex("""msg ID-(\d+) \{correlationId=ID-(\d+)}""")
        val mismatches = captured.filter { entry ->
            val match = line.matchEntire(entry.message)
            match == null || match.groupValues[1] != match.groupValues[2]
        }
        assertEquals(emptyList(), mismatches)
        assertEquals(
            (0 until 500).associate { "msg ID-$it" to 3 },
            captured.groupingBy { it.message.substringBefore(" {") }.eachCount()
        )
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty(), "MDC leaked into the runBlocking thread")
    }

    @Test
    fun `a coroutine without MDCContext does not see the MDC of another coroutine on the same thread`() {
        captureCustom()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { single ->
            runBlocking {
                val gate = CompletableDeferred<Unit>()
                val a = launch(single + MDCContext(mapOf("correlationId" to "A"))) {
                    KLogger.info("T") { "a1" }
                    gate.await() // suspends, b runs on the same thread meanwhile
                    KLogger.info("T") { "a2" }
                }
                val b = launch(single) {
                    KLogger.info("T") { "b" }
                    gate.complete(Unit)
                }
                joinAll(a, b)
            }
        }

        assertEquals(listOf("a1 {correlationId=A}", "b", "a2 {correlationId=A}"), captured.map { it.message })
    }

    @Test
    fun `a pool thread created inside an MDCContext does not pass the MDC on to later coroutines`() {
        captureCustom()
        Executors.newFixedThreadPool(1).asCoroutineDispatcher().use { pool ->
            runBlocking {
                withContext(MDCContext(mapOf("correlationId" to "ALT"))) {
                    // Dispatching the first task creates the worker thread, on this thread, while its MDC is ALT
                    launch(pool) { KLogger.info("T") { "request" } }.join()
                }
                // A later coroutine without MDCContext on that same worker
                withContext(pool) { KLogger.info("T") { "later" } }
            }
        }

        assertEquals(listOf("request {correlationId=ALT}", "later"), captured.map { it.message })
    }

    @Test
    fun `nested MDCContext adds keys and the outer context is restored afterwards`() {
        captureCustom()

        runBlocking(MDCContext(mapOf("correlationId" to "C1"))) {
            withContext(Dispatchers.IO + MDCContext(MDC.getCopyOfContextMap().orEmpty() + ("step" to "fetch"))) {
                KLogger.info("T") { "inner" }
            }
            KLogger.info("T") { "outer" }
        }
        KLogger.info("T") { "after" }

        assertEquals(
            listOf("inner {correlationId=C1, step=fetch}", "outer {correlationId=C1}", "after"),
            captured.map { it.message },
        )
    }

    @Test
    fun `coroutines with and without MDCContext interleaved on a small pool never mix their MDC`() {
        captureCustom()
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { pool ->
            runBlocking {
                (0 until 300).map { i ->
                    val context = if (i % 2 == 0) pool + MDCContext(mapOf("correlationId" to "ID-$i")) else pool
                    launch(context) {
                        repeat(3) {
                            delay(Random.nextLong(0, 3))
                            yield()
                            KLogger.info("T") { "msg $i" }
                        }
                    }
                }.joinAll()
            }
        }

        assertEquals(900, captured.size)
        val wrong = captured.filter { entry ->
            val i = entry.message.removePrefix("msg ").substringBefore(" ").toInt()
            val expected = if (i % 2 == 0) "msg $i {correlationId=ID-$i}" else "msg $i"
            entry.message != expected
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `console line in the default format as seen by a real consumer`() {
        KLogger.configure { logToConsole() }

        val stdout = captureStdoutUtf8 {
            runBlocking {
                withContext(MDCContext(mapOf("correlationId" to "c-1", "system" to "checkout"))) {
                    OrderService().loadOrder()
                }
            }
        }

        val expected =
            Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} INFO/OrderService: loadOrder → 200 \{correlationId=c-1, system=checkout}""")
        assertTrue(expected.matches(stdout.trim()), stdout)
        println("Smoke test console line: ${stdout.trim()}")
    }

    /** Like [captureStdout], but always UTF-8, independent of the platform charset. */
    private fun captureStdoutUtf8(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString("UTF-8")
    }
}

private class OrderService {
    fun loadOrder() = infoLog { "loadOrder → 200" }
}
