package bayern.kickner.klogger

import kotlin.test.*

class StaticsTest {

    private val received = mutableListOf<Triple<KLogger.Level, String, String>>()

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        KLogger.configure { logToCustom { level, tag, message -> received.add(Triple(level, tag, message)) } }
    }

    @AfterTest
    fun tearDown() = KLogger.resetForTest()

    @Test
    fun `warnLog with exception appends the stack trace`() {
        val ex = RuntimeException("x")

        warnLog(ex) { "msg" }

        assertEquals(Triple(KLogger.Level.WARN, "StaticsTest", "msg\n" + ex.stackTraceToString()), received.single())
        assertTrue(received.single().third.startsWith("msg\njava.lang.RuntimeException: x\n\tat "))
    }

    @Test
    fun `warnLog with exception can skip the stack trace`() {
        warnLog(RuntimeException("y"), printStackTrace = false) { "short" }
        warnLog(IllegalStateException(), printStackTrace = false) { "no message" }

        assertEquals("short\nException: y", received[0].third)
        assertEquals("no message\nException: IllegalStateException", received[1].third)
    }

    @Test
    fun `warnLog with exception stays lazy below minLevel`() {
        KLogger.configure { minLevel = KLogger.Level.ERROR }
        var evaluated = false

        warnLog(RuntimeException("x")) {
            evaluated = true
            "msg"
        }

        assertFalse(evaluated)
        assertTrue(received.isEmpty())
    }
}
