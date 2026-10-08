package bayern.kickner.klogger

import bayern.kickner.klogger.http.HttpLogAppender
import bayern.kickner.klogger.http.logToHttp
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point for [ShutdownHookTest]'s child JVM. Args: target URL and a mode (`hook`, `nohook`, `hook-then-off`).
 * Logs one line into an HTTP appender whose own flush loop never fires, then returns right away, so the line
 * reaches the server only if the shutdown hook flushes it.
 */
object ShutdownHookMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val (url, mode) = args
        val appender =
            HttpLogAppender(url, { batch -> batch.joinToString("\n") { it.message } }, initialFlushInterval = 1.hours)
        KLogger.configure {
            if (mode == "hook" || mode == "hook-then-off") flushOnShutdown = 2.seconds
            logToHttp(appender)
        }
        if (mode == "hook-then-off") KLogger.configure { flushOnShutdown = null }
        KLogger.info("main") { "bye" }
    }
}
