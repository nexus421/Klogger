package bayern.kickner.klogger.slf4j

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.KLogger.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
