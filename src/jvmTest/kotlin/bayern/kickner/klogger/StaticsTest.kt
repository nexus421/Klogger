package bayern.kickner.klogger

import kotlin.test.*

class StaticsTest {

    private class SampleService {
        fun logDebug(msg: String) = debugLog { msg }
        fun logInfo(msg: String) = infoLog { msg }
        fun logWarn(msg: String) = warnLog { msg }
        fun logError(msg: String, ex: Throwable? = null, printStackTrace: Boolean = true, sendAsCrash: Boolean = false) {
            errorLog(msg, ex = ex, printStackTrace = printStackTrace, sendAsCrash = sendAsCrash)
        }
        fun logErrorEx(ex: Throwable) = errorLog(ex)
    }

    @BeforeTest
    fun setUp() {
        KLogger.resetForTest()
    }

    @Test
    fun `DEFAULT_LOG_TAG returns simple class name`() {
        val service = SampleService()
        assertEquals("SampleService", service.DEFAULT_LOG_TAG)
    }

    @Test
    fun `debugLog uses DEFAULT_LOG_TAG and dispatches to KLogger`() {
        var recordedTag: String? = null
        var recordedLevel: KLogger.Level? = null
        var recordedMessage: String? = null

        KLogger.configure {
            logToCustom { level, tag, message ->
                recordedLevel = level
                recordedTag = tag
                recordedMessage = message
            }
        }

        SampleService().logDebug("debugging")

        assertEquals(KLogger.Level.DEBUG, recordedLevel)
        assertEquals("SampleService", recordedTag)
        assertEquals("debugging", recordedMessage)
    }

    @Test
    fun `infoLog uses DEFAULT_LOG_TAG and dispatches to KLogger`() {
        var recordedTag: String? = null
        var recordedLevel: KLogger.Level? = null
        var recordedMessage: String? = null

        KLogger.configure {
            logToCustom { level, tag, message ->
                recordedLevel = level
                recordedTag = tag
                recordedMessage = message
            }
        }

        SampleService().logInfo("informational")

        assertEquals(KLogger.Level.INFO, recordedLevel)
        assertEquals("SampleService", recordedTag)
        assertEquals("informational", recordedMessage)
    }

    @Test
    fun `warnLog uses DEFAULT_LOG_TAG and dispatches to KLogger`() {
        var recordedTag: String? = null
        var recordedLevel: KLogger.Level? = null
        var recordedMessage: String? = null

        KLogger.configure {
            logToCustom { level, tag, message ->
                recordedLevel = level
                recordedTag = tag
                recordedMessage = message
            }
        }

        SampleService().logWarn("warning")

        assertEquals(KLogger.Level.WARN, recordedLevel)
        assertEquals("SampleService", recordedTag)
        assertEquals("warning", recordedMessage)
    }

    @Test
    fun `errorLog dispatches ERROR by default and CRASH when sendAsCrash is true`() {
        val levels = mutableListOf<KLogger.Level>()
        val messages = mutableListOf<String>()

        KLogger.configure {
            logToCustom { level, _, message ->
                levels.add(level)
                messages.add(message)
            }
        }

        val service = SampleService()
        service.logError("regular error", sendAsCrash = false)
        service.logError("fatal crash", sendAsCrash = true)

        assertEquals(listOf(KLogger.Level.ERROR, KLogger.Level.CRASH), levels)
        assertEquals(listOf("regular error", "fatal crash"), messages)
    }

    @Test
    fun `errorLog with exception includes stack trace when printStackTrace is true`() {
        var recordedMessage = ""
        KLogger.configure {
            logToCustom { _, _, message -> recordedMessage = message }
        }

        val ex = IllegalArgumentException("invalid value")
        SampleService().logError("operation failed", ex = ex, printStackTrace = true)

        assertTrue(recordedMessage.startsWith("operation failed\n"))
        assertTrue(recordedMessage.contains("IllegalArgumentException: invalid value"))
        assertTrue(recordedMessage.contains("at bayern.kickner.klogger.StaticsTest"))
    }

    @Test
    fun `errorLog with exception omits stack trace when printStackTrace is false`() {
        var recordedMessage = ""
        KLogger.configure {
            logToCustom { _, _, message -> recordedMessage = message }
        }

        val ex = IllegalArgumentException("invalid value")
        SampleService().logError("operation failed", ex = ex, printStackTrace = false)

        assertEquals("operation failed\nException: invalid value", recordedMessage)
    }

    @Test
    fun `errorLog with only exception logs full stack trace`() {
        var recordedMessage = ""
        KLogger.configure {
            logToCustom { _, _, message -> recordedMessage = message }
        }

        val ex = IllegalStateException("broken state")
        SampleService().logErrorEx(ex)

        assertTrue(recordedMessage.contains("IllegalStateException: broken state"))
        assertTrue(recordedMessage.contains("at bayern.kickner.klogger.StaticsTest"))
    }

    @Test
    fun `staticLog dispatches correct level and tag`() {
        val records = mutableListOf<Triple<KLogger.Level, String, String>>()
        KLogger.configure {
            logToCustom { level, tag, message -> records.add(Triple(level, tag, message)) }
        }

        staticLog(KLogger.Level.DEBUG, "StaticTag") { "dbg" }
        staticLog(KLogger.Level.INFO, "StaticTag") { "inf" }
        staticLog(KLogger.Level.WARN, "StaticTag") { "wrn" }
        staticLog(KLogger.Level.ERROR, "StaticTag") { "err" }
        staticLog(KLogger.Level.CRASH, "StaticTag") { "crs" }

        assertEquals(
            listOf(
                Triple(KLogger.Level.DEBUG, "StaticTag", "dbg"),
                Triple(KLogger.Level.INFO, "StaticTag", "inf"),
                Triple(KLogger.Level.WARN, "StaticTag", "wrn"),
                Triple(KLogger.Level.ERROR, "StaticTag", "err"),
                Triple(KLogger.Level.CRASH, "StaticTag", "crs"),
            ),
            records
        )
    }
}
