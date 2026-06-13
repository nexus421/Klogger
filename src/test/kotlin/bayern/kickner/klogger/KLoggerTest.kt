package bayern.kickner.klogger

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KLoggerTest {

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
    }

    @Test
    fun `messages below minLevel are not dispatched`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            minLevel = KLogger.Level.WARN
            logToCustom { _, _, message -> received.add(message) }
        }

        KLogger.debug("tag") { "debug" }
        KLogger.info("tag") { "info" }
        KLogger.warn("tag") { "warn" }
        KLogger.error("tag") { "error" }

        assertEquals(listOf("warn", "error"), received)
    }

    @Test
    fun `debug mode bypasses minLevel`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            minLevel = KLogger.Level.ERROR
            debug = true
            logToCustom { _, _, message -> received.add(message) }
        }

        KLogger.debug("tag") { "debug" }
        KLogger.info("tag") { "info" }

        assertEquals(listOf("debug", "info"), received)
    }

    @Test
    fun `all destinations receive the same message`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        KLogger.configure {
            logToCustom { _, _, message -> first.add(message) }
            logToCustom { _, _, message -> second.add(message) }
        }

        KLogger.info("tag") { "hello" }

        assertEquals(listOf("hello"), first)
        assertEquals(listOf("hello"), second)
    }

    @Test
    fun `throwing destination does not prevent other destinations from receiving`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            logToCustom { _, _, _ -> throw RuntimeException("simulated failure") }
            logToCustom { _, _, message -> received.add(message) }
        }

        KLogger.info("tag") { "test" }

        assertEquals(listOf("test"), received)
    }

    @Test
    fun `configure preserves destinations from previous calls`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            logToCustom { _, _, message -> received.add(message) }
        }
        KLogger.configure {
            // second call adds nothing new, but must not wipe existing destinations
        }

        KLogger.info("tag") { "hello" }

        assertEquals(listOf("hello"), received)
    }

    @Test
    fun `correct level is passed to destination`() {
        val levels = mutableListOf<KLogger.Level>()
        KLogger.configure {
            logToCustom { level, _, _ -> levels.add(level) }
        }

        KLogger.debug("t") { "d" }
        KLogger.info("t") { "i" }
        KLogger.warn("t") { "w" }
        KLogger.error("t") { "e" }
        KLogger.crash("t") { "c" }

        assertEquals(
            listOf(
                KLogger.Level.DEBUG,
                KLogger.Level.INFO,
                KLogger.Level.WARN,
                KLogger.Level.ERROR,
                KLogger.Level.CRASH
            ),
            levels
        )
    }

    @Test
    fun `tag is passed correctly to destination`() {
        val tags = mutableListOf<String>()
        KLogger.configure {
            logToCustom { _, tag, _ -> tags.add(tag) }
        }

        KLogger.info("MyTag") { "msg" }

        assertEquals(listOf("MyTag"), tags)
    }

    @Test
    fun `message lambda is not evaluated when level is filtered`() {
        var evaluated = false
        KLogger.configure {
            minLevel = KLogger.Level.ERROR
            logToCustom { _, _, _ -> }
        }

        KLogger.debug("tag") {
            evaluated = true
            "expensive message"
        }

        assertFalse(evaluated, "Message lambda must not be evaluated for filtered levels")
    }
}
