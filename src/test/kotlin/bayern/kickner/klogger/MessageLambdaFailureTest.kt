package bayern.kickner.klogger

import kotlin.test.*

class MessageLambdaFailureTest {

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        KLogger.resetForTest()
    }

    private val received = mutableListOf<Triple<KLogger.Level, String, String>>()

    private fun configure() = KLogger.configure { logToCustom { level, tag, message -> received.add(Triple(level, tag, message)) } }

    @Test
    fun `a throwing message lambda does not reach the caller`() {
        configure()

        KLogger.info("tag") { error("boom") } // must not throw
    }

    @Test
    fun `a throwing message lambda is logged as a line that names the failure`() {
        configure()

        KLogger.warn("tag") { error("boom") }

        val (level, tag, message) = received.single()
        assertEquals(KLogger.Level.WARN, level)
        assertEquals("tag", tag)
        assertContains(message, "boom")
    }

    @Test
    fun `logging keeps working after a throwing message lambda`() {
        configure()

        KLogger.info("tag") { error("boom") }
        KLogger.info("tag") { "fine" }

        assertEquals("fine", received.last().third)
    }

    @Test
    fun `a filtered message lambda is still not evaluated`() {
        KLogger.configure {
            minLevel = KLogger.Level.ERROR
            logToCustom { level, tag, message -> received.add(Triple(level, tag, message)) }
        }
        var evaluated = false

        KLogger.info("tag") { evaluated = true; "x" }

        assertFalse(evaluated)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `warnLog helper with a throwing message lambda does not reach the caller`() {
        configure()

        warnLog(RuntimeException("cause")) { error("boom") } // withThrowable(msg(), ...) runs inside the lambda
    }
}
