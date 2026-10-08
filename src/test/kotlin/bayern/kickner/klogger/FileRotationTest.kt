package bayern.kickner.klogger

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class FileRotationTest {

    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
        dir = Files.createTempDirectory("klogger-rotation").toFile()
        file = File(dir, "app.log")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
        KLogger.resetForTest()
    }

    private fun lineNumbers(f: File) =
        Regex("line-(\\d+)").findAll(f.readText()).map { it.groupValues[1].toInt() }.toList()

    @Test
    fun `file rotates at maxFileSize and keeps maxBackupFiles`() {
        KLogger.configure { logToFile(file, maxFileSize = 200, maxBackupFiles = 2) }

        repeat(50) { i -> KLogger.info("t") { "line-$i" } }

        assertEquals(listOf("app.log", "app.log.1", "app.log.2"), dir.list()!!.sorted())
        dir.listFiles()!!.forEach { assertTrue(it.length() <= 200, "${it.name} has ${it.length()} bytes") }
        // Oldest to newest, the kept lines are one gap-free run ending with the last line
        val kept = lineNumbers(File(dir, "app.log.2")) + lineNumbers(File(dir, "app.log.1")) + lineNumbers(file)
        assertEquals((kept.first()..49).toList(), kept)
    }

    @Test
    fun `size is counted in bytes, not characters`() {
        KLogger.configure { logToFile(file, maxFileSize = 200, maxBackupFiles = 5) }

        repeat(20) { i -> KLogger.info("t") { "line-$i " + "ü".repeat(20) } }

        dir.listFiles()!!.forEach { assertTrue(it.length() <= 200, "${it.name} has ${it.length()} bytes") }
    }

    @Test
    fun `an existing file counts towards the limit and an oversized line is still written`() {
        file.writeText("x".repeat(150) + "\n")
        KLogger.configure { logToFile(file, maxFileSize = 100, maxBackupFiles = 1) }

        KLogger.info("t") { "y".repeat(500) }

        assertEquals(151, File(dir, "app.log.1").length())
        assertContains(file.readText(), "y".repeat(500))
    }

    @Test
    fun `failed rotation keeps writing and reports once on stderr`() {
        // A non-empty directory in the backup's place makes both delete and rename fail
        File(dir, "app.log.1").mkdirs()
        File(dir, "app.log.1/blocker").writeText("x")
        KLogger.configure { logToFile(file, maxFileSize = 100, maxBackupFiles = 1) }

        val stderr = captureStderr { repeat(20) { i -> KLogger.info("t") { "line-$i" } } }

        assertEquals((0 until 20).toList(), lineNumbers(file))
        assertEquals(1, Regex("could not rotate").findAll(stderr).count(), stderr)
    }

    @Test
    fun `a rotation that fails halfway does not overwrite a backup`() {
        // The oldest backup can't be deleted, so shifting app.log.1 fails; renaming app.log onto app.log.1
        // afterwards would silently replace it and lose its lines.
        File(dir, "app.log.2").mkdirs()
        File(dir, "app.log.2/blocker").writeText("x")
        KLogger.configure { logToFile(file, maxFileSize = 100, maxBackupFiles = 2) }

        val stderr = captureStderr { repeat(12) { i -> KLogger.info("t") { "line-$i" } } }

        val backup1 = File(dir, "app.log.1")
        val kept = (if (backup1.isFile) lineNumbers(backup1) else emptyList()) + lineNumbers(file)
        assertEquals((0 until 12).toList(), kept)
        assertEquals(1, Regex("could not rotate").findAll(stderr).count(), stderr)
    }

    @Test
    fun `a huge maxBackupFiles does not make rotation slow`() {
        KLogger.configure { logToFile(file, maxFileSize = 100, maxBackupFiles = 1_000_000) }

        val took = measureTime { repeat(20) { i -> KLogger.info("t") { "line-$i" } } }

        assertTrue(took < 1.seconds, "20 log calls with rotation took $took")
        val backups =
            dir.list()!!.filter { it.startsWith("app.log.") }.sortedByDescending { it.substringAfterLast('.').toInt() }
        val kept = backups.flatMap { lineNumbers(File(dir, it)) } + lineNumbers(file)
        assertEquals((0 until 20).toList(), kept)
    }

    @Test
    fun `without maxFileSize the file is never rotated`() {
        KLogger.configure { logToFile(file) }

        repeat(1000) { i -> KLogger.info("t") { "line-$i" } }

        assertEquals(listOf("app.log"), dir.list()!!.toList())
        assertEquals(1000, lineNumbers(file).size)
    }

    @Test
    fun `invalid rotation settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { KLogger.configure { logToFile(file, maxFileSize = 0) } }
        assertFailsWith<IllegalArgumentException> { KLogger.configure { logToFile(file, maxFileSize = -1) } }
        assertFailsWith<IllegalArgumentException> {
            KLogger.configure { logToFile(file, maxFileSize = 100, maxBackupFiles = 0) }
        }
    }
}
