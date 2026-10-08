package bayern.kickner.klogger.slf4j

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.KLogger.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs only in the `testWithoutSlf4j` task, where slf4j-api is removed from the classpath.
 * Must not reference any SLF4J type.
 */
class WithoutSlf4jTest {

    @Test
    fun `slf4j is really absent`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.slf4j.LoggerFactory") }
    }

    @Test
    fun `Klogger and slf4jBridge work without SLF4J on the classpath`() {
        KLogger.resetForTest()
        val received = mutableListOf<String>()
        KLogger.configure {
            logToCustom { _, _, message -> received += message }
            slf4jBridge {
                minLevel = Level.WARN
                level("io.ktor", Level.DEBUG)
                off("com.zaxxer.hikari")
            }
        }

        KLogger.info("tag") { "hello" }

        assertEquals(listOf("hello"), received)
    }

    @Test
    fun `0_4_0 options work without SLF4J on the classpath`() {
        KLogger.resetForTest()
        val received = mutableListOf<String>()
        KLogger.configure {
            format(KLogger.defaultFormat("dd.MM.yyyy HH:mm:ss.SSS", threadName = true))
            logToCustom(Level.WARN) { _, _, message -> received += message }
            logToConsole(minLevel = Level.CRASH)
        }

        KLogger.info("tag") { "filtered" }
        KLogger.warn("tag") { "kept" }

        assertEquals(listOf("kept"), received)
        assertTrue(KLogger.flush())
        KLogger.resetForTest()
    }
}
