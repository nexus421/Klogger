package bayern.kickner.klogger

import kotlin.test.Test

class CommonLoggerTest {
    @Test
    fun smoke_logging_calls_do_not_throw() {
        Logger.configure { logToConsole() }
        Logger.debug("CommonTest") { "debug" }
        Logger.info("CommonTest") { "info" }
        Logger.warn("CommonTest") { "warn" }
        Logger.error("CommonTest") { "error" }
        Logger.crash("CommonTest") { "crash" }
    }
}
