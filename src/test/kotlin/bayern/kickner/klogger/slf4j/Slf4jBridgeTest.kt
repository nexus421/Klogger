package bayern.kickner.klogger.slf4j

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.KLogger.Level
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Slf4jBridgeTest {

    private data class Entry(val level: Level, val tag: String, val message: String)

    private val captured = CopyOnWriteArrayList<Entry>()

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        MDC.clear()
        KLogger.configure {
            minLevel = Level.DEBUG
            logToCustom { level, tag, message -> captured += Entry(level, tag, message) }
            slf4jBridge() // defaults
        }
    }

    @Test
    fun `parameterized message is formatted and tag is abbreviated`() {
        LoggerFactory.getLogger("io.ktor.server.Application").info("Responding at {}:{}", "0.0.0.0", 8080)

        assertEquals(listOf(Entry(Level.INFO, "i.k.s.Application", "Responding at 0.0.0.0:8080")), captured)
    }

    @Test
    fun `logger names are abbreviated to tags`() {
        assertEquals("i.k.s.Application", abbreviateLoggerName("io.ktor.server.Application"))
        assertEquals("Main", abbreviateLoggerName("Main"))
        assertEquals(".Hidden", abbreviateLoggerName(".Hidden"))
        assertEquals("", abbreviateLoggerName(""))
    }

    @Test
    fun `bridge minLevel filters independently of KLogger minLevel`() {
        val log = LoggerFactory.getLogger("some.lib.Thing")
        assertFalse(log.isDebugEnabled) // bridge default is INFO although KLogger is at DEBUG
        log.debug("hidden")
        log.info("shown")

        assertEquals(listOf("shown"), captured.map { it.message })
    }

    @Test
    fun `KLogger minLevel is honoured as well`() {
        KLogger.configure { minLevel = Level.ERROR }
        val log = LoggerFactory.getLogger("some.lib.Thing")
        assertFalse(log.isWarnEnabled)
        log.warn("hidden")
        log.error("shown")

        assertEquals(listOf("shown"), captured.map { it.message })
    }

    @Test
    fun `KLogger debug mode does not lift the bridge threshold`() {
        KLogger.configure { minLevel = Level.ERROR; debug = true }
        val log = LoggerFactory.getLogger("some.lib.Thing")
        assertFalse(log.isDebugEnabled)
        log.debug("hidden")
        log.info("shown") // KLogger minLevel ERROR is bypassed by debug, bridge INFO still applies

        assertEquals(listOf("shown"), captured.map { it.message })
    }

    @Test
    fun `longest prefix override wins and off drops everything`() {
        KLogger.configure {
            slf4jBridge {
                level("io.netty", Level.WARN)
                level("io.netty.handler", Level.DEBUG)
                off("com.zaxxer.hikari.pool")
            }
        }
        LoggerFactory.getLogger("io.netty.channel.X").info("netty info hidden")
        LoggerFactory.getLogger("io.netty.channel.X").warn("netty warn")
        LoggerFactory.getLogger("io.netty.handler.Y").debug("handler debug")
        LoggerFactory.getLogger("io.nettyfoo.Z").info("not a netty child")
        LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool").error("off")
        LoggerFactory.getLogger("com.zaxxer.hikari.pool").error("off, exact name")

        assertEquals(listOf("netty warn", "handler debug", "not a netty child"), captured.map { it.message })
        assertFalse(LoggerFactory.getLogger("com.zaxxer.hikari.pool.HikariPool").isErrorEnabled)
    }

    @Test
    fun `config change is picked up by already created loggers`() {
        val log = LoggerFactory.getLogger("late.Change")
        assertFalse(log.isDebugEnabled)
        KLogger.configure { slf4jBridge { minLevel = Level.DEBUG } }
        assertTrue(log.isDebugEnabled)
        KLogger.configure { slf4jBridge() } // calling it again replaces, it does not merge
        assertFalse(log.isDebugEnabled)
    }

    @Test
    fun `trace is always dropped`() {
        KLogger.configure { debug = true; slf4jBridge { minLevel = Level.DEBUG } }
        val log = LoggerFactory.getLogger("t.Trace")
        assertFalse(log.isTraceEnabled)
        log.trace("dropped")
        log.trace("dropped {}", 1)
        log.atTrace().log("dropped fluent")

        assertTrue(captured.isEmpty())
    }

    @Test
    fun `throwable is appended as stack trace, also when passed as last argument`() {
        val log = LoggerFactory.getLogger("x.Err")
        log.error("plain", IllegalStateException("boom1"))
        log.error("with arg {}", 42, IllegalStateException("boom2"))
        log.error("single {}", IllegalStateException("boom3") as Any)

        assertEquals(3, captured.size)
        assertTrue(captured.all { it.level == Level.ERROR })
        assertTrue(captured[0].message.startsWith("plain\njava.lang.IllegalStateException: boom1"))
        assertTrue(captured[1].message.startsWith("with arg 42\njava.lang.IllegalStateException: boom2"))
        assertTrue(captured[2].message.contains("java.lang.IllegalStateException: boom3"))
    }

    @Test
    fun `MDC is appended when present`() {
        MDC.put("call-id", "abc")
        LoggerFactory.getLogger("x.Mdc").info("request")
        MDC.clear()
        LoggerFactory.getLogger("x.Mdc").info("no mdc")

        assertEquals(listOf("request {call-id=abc}", "no mdc"), captured.map { it.message })
    }

    @Test
    fun `MDC and throwable are combined`() {
        MDC.put("call-id", "abc")
        LoggerFactory.getLogger("x.Mdc").error("failed", IllegalStateException("boom"))

        assertTrue(captured.single().message.startsWith("failed {call-id=abc}\njava.lang.IllegalStateException: boom"))
    }

    @Test
    fun `fluent API is routed and respects the threshold`() {
        val log = LoggerFactory.getLogger("x.Fluent")
        log.atWarn().addArgument("v").log("fluent {}")
        log.atDebug().log("hidden")

        assertEquals(listOf(Entry(Level.WARN, "x.Fluent", "fluent v")), captured)
    }

    @Test
    fun `message is only built when it passes the level checks`() {
        val builds = AtomicInteger()
        val lazyArg = object {
            override fun toString(): String = "built".also { builds.incrementAndGet() }
        }
        val log = LoggerFactory.getLogger("x.Lazy")
        log.debug("value {}", lazyArg)
        assertEquals(0, builds.get())

        log.info("value {}", lazyArg)
        assertEquals(1, builds.get())
        assertEquals(listOf("value built"), captured.map { it.message })
    }

    @Test
    fun `destination logging via SLF4J does not recurse`() {
        val calls = AtomicInteger()
        KLogger.configure {
            logToCustom { _, tag, _ ->
                if (tag == "x.Loop") {
                    calls.incrementAndGet()
                    LoggerFactory.getLogger("x.Loop").warn("from inside a destination")
                }
            }
        }
        LoggerFactory.getLogger("x.Loop").warn("outer")
        LoggerFactory.getLogger("x.Loop").warn("second outer") // guard is released again afterwards

        assertEquals(2, calls.get())
        assertEquals(listOf("outer", "second outer"), captured.map { it.message })
    }

    @Test
    fun `a throwing toString never reaches the caller`() {
        val evil = object {
            override fun toString(): String = throw RuntimeException("nope")
        }
        LoggerFactory.getLogger("x.Evil").info("value {}", evil)

        assertEquals(1, captured.size) // SLF4J renders "[FAILED toString()]"
    }
}
