package bayern.kickner.klogger

actual object Logger {
    actual enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    // Einfache Standardformatierung ohne Zeitstempel
    actual fun formatLogDefault(level: Level, tag: String, message: String): String =
        "${level.name}/$tag: $message"

    // Für JS: einfach auf die Konsole schreiben
    private fun write(level: Level, tag: String, msg: String) {
        val line = formatLogDefault(level, tag, msg)
        // Einfach stdout verwenden
        println(line)
    }

    actual fun configure(block: LoggerDsl.() -> Unit) {
        // Aktuell keine konfigurierbaren Ziele auf JS – Standardausgabe reicht.
        LoggerDsl().apply(block)
    }

    actual fun debug(tag: String, msg: () -> String) = write(Level.DEBUG, tag, msg())
    actual fun info(tag: String, msg: () -> String) = write(Level.INFO, tag, msg())
    actual fun warn(tag: String, msg: () -> String) = write(Level.WARN, tag, msg())
    actual fun error(tag: String, msg: () -> String) = write(Level.ERROR, tag, msg())
    actual fun crash(tag: String, msg: () -> String) = write(Level.CRASH, tag, msg())

    actual class LoggerDsl internal constructor() {
        // Ziele (No-ops auf JS)
        actual fun logToConsole() { /* stdout ist implizit aktiv */
        }

        actual fun logToFile(@Suppress("UNUSED_PARAMETER") path: String) { /* nicht unterstützt */
        }

        actual fun logToCustom(@Suppress("UNUSED_PARAMETER") block: (level: Level, tag: String, message: String) -> Unit) { /* nicht unterstützt */
        }

        actual fun logToCachedForwarding(
            @Suppress("UNUSED_PARAMETER") cacheDirPath: String,
            @Suppress("UNUSED_PARAMETER") target: (level: Level, tag: String, message: String) -> Unit,
            @Suppress("UNUSED_PARAMETER") maxFlushPerCall: Int
        ) { /* nicht unterstützt */
        }

        // Filter/Flags – auf JS keine echte Filterung, aber wir halten die API paritätisch
        actual var minLevel: Level = Level.DEBUG
        actual var debug: Boolean = true

        internal fun build() = Unit
    }
}
