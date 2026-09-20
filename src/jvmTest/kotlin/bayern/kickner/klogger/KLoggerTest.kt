package bayern.kickner.klogger

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KLoggerTest {

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
    }

    @Test
    fun `warning is printed to stderr once when logging before configure is called`() {
        val stderr = captureStderr {
            KLogger.info("tag") { "first" }
            KLogger.info("tag") { "second" }
        }

        val occurrences = Regex("No destinations configured").findAll(stderr).count()
        assertEquals(1, occurrences, "Warning should be printed exactly once")
    }
}
