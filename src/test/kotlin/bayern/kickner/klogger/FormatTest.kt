package bayern.kickner.klogger

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.*

class FormatTest {

    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        dir = Files.createTempDirectory("klogger-format").toFile()
        file = File(dir, "app.log")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
        KLogger.resetForTest()
    }

    private fun fileLine() = file.readText().trimEnd()

    @Test
    fun `timestamp pattern with milliseconds`() {
        KLogger.configure {
            format(KLogger.defaultFormat(timestampPattern = "dd.MM.yyyy HH:mm:ss.SSS"))
            logToFile(file)
        }

        KLogger.info("Tag") { "hi" }

        assertTrue(
            Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}\.\d{3} INFO/Tag: hi""").matches(fileLine()),
            fileLine()
        )
    }

    @Test
    fun `timestamp pattern with zone offset`() {
        KLogger.configure {
            format(KLogger.defaultFormat(timestampPattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
            logToFile(file)
        }

        KLogger.info("Tag") { "hi" }

        val iso = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2}) INFO/Tag: hi""")
        assertTrue(iso.matches(fileLine()), fileLine())
    }

    @Test
    fun `thread name of the logging thread`() {
        KLogger.configure {
            format(KLogger.defaultFormat(threadName = true))
            logToFile(file)
        }

        thread(name = "my-thread") { KLogger.info("Tag") { "hi" } }.join()

        assertTrue(
            Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} \[my-thread] INFO/Tag: hi""").matches(fileLine()),
            fileLine()
        )
    }

    @Test
    fun `defaultFormat without arguments matches formatLogDefault`() {
        val defaultShape = Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} WARN/T: m""")

        assertTrue(defaultShape.matches(KLogger.defaultFormat()(KLogger.Level.WARN, "T", "m")))
        assertTrue(defaultShape.matches(KLogger.formatLogDefault(KLogger.Level.WARN, "T", "m")))
    }

    @Test
    fun `custom format applies to console and file`() {
        KLogger.configure {
            format { level, tag, message -> "<$level|$tag|$message>" }
            logToFile(file)
            logToConsole()
        }

        val stdout = captureStdout { KLogger.info("T") { "m" } }

        assertEquals("<INFO|T|m>", fileLine())
        assertEquals("<INFO|T|m>", stdout.trim())
    }

    @Test
    fun `format does not touch the raw message of custom destinations`() {
        val received = mutableListOf<String>()
        KLogger.configure {
            format { _, _, _ -> "formatted" }
            logToCustom { _, _, message -> received.add(message) }
        }

        KLogger.info("T") { "raw" }

        assertEquals(listOf("raw"), received)
    }

    @Test
    fun `a throwing format falls back to the default format`() {
        KLogger.configure {
            format { _, _, _ -> error("broken format") }
            logToFile(file)
        }

        KLogger.info("T") { "still written" }

        assertTrue(
            Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} INFO/T: still written""").matches(fileLine()),
            fileLine()
        )
    }

    @Test
    fun `an invalid timestamp pattern fails inside configure`() {
        assertFailsWith<IllegalArgumentException> {
            KLogger.configure { format(KLogger.defaultFormat("dd.MM.yyyy bbb")) }
        }
    }

    @Test
    fun `format survives later configure calls`() {
        KLogger.configure { format { _, _, message -> "custom:$message" } }
        KLogger.configure { logToFile(file) }

        KLogger.info("T") { "m" }

        assertEquals("custom:m", fileLine())
    }
}
