package bayern.kickner.klogger

actual object Logger {
    actual enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    actual fun formatLogDefault(level: Level, tag: String, message: String): String =
        "${level.name}/$tag: $message"

    private fun write(level: Level, tag: String, msg: String) {
        val line = formatLogDefault(level, tag, msg)
        println(line)
    }

    actual fun configure(block: LoggerDsl.() -> Unit) {
        LoggerDsl().apply(block)
    }

    actual fun debug(tag: String, msg: () -> String) = write(Level.DEBUG, tag, msg())
    actual fun info(tag: String, msg: () -> String) = write(Level.INFO, tag, msg())
    actual fun warn(tag: String, msg: () -> String) = write(Level.WARN, tag, msg())
    actual fun error(tag: String, msg: () -> String) = write(Level.ERROR, tag, msg())
    actual fun crash(tag: String, msg: () -> String) = write(Level.CRASH, tag, msg())

    actual class LoggerDsl internal constructor() {
        fun logToConsole() { /* stdout implizit */
        }

        fun logToFile(@Suppress("UNUSED_PARAMETER") path: String) { /* nicht unterstützt */
        }

        fun logToCustom(@Suppress("UNUSED_PARAMETER") block: (level: Level, tag: String, message: String) -> Unit) { /* nicht unterstützt */
        }

        fun logToCachedForwarding(
            @Suppress("UNUSED_PARAMETER") cacheDirPath: String,
            @Suppress("UNUSED_PARAMETER") target: (level: Level, tag: String, message: String) -> Unit,
            @Suppress("UNUSED_PARAMETER") maxFlushPerCall: Int
        ) { /* nicht unterstützt */
        }

        var minLevel: Level = Level.DEBUG
        var debug: Boolean = true
    }
}
