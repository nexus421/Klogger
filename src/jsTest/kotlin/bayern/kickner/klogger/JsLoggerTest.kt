package bayern.kickner.klogger

import kotlin.test.Test

class JsLoggerTest {
    @Test
    fun smoke_logging() {
        Logger.configure { logToConsole() }
        Logger.debug("JsTest") { "dbg" }
        Logger.info("JsTest") { "inf" }
        Logger.warn("JsTest") { "wrn" }
        Logger.error("JsTest") { "err" }
        Logger.crash("JsTest") { "crh" }
    }
}
