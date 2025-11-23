package bayern.kickner.klogger

import bayern.kickner.klogger.Logger.configure


/**
 * Gemeinsame Logger-API für alle Plattformen. Die tatsächliche Implementierung
 * ist pro Plattform unterschiedlich (actual).
 */
expect object Logger {
    /** Log-Level in aufsteigender Schwere. */
    enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    /**
     * Konfiguration per DSL. Auf JVM voll funktionsfähig (Konsole, Datei, Cache usw.).
     * Auf JS/iOS aktuell nur Standardausgabe; der Block kann ignoriert werden.
     */
    fun configure(block: LoggerDsl.() -> Unit)

    fun debug(tag: String, msg: () -> String)
    fun info(tag: String, msg: () -> String)
    fun warn(tag: String, msg: () -> String)
    fun error(tag: String, msg: () -> String)
    fun crash(tag: String, msg: () -> String)

    /** Formatierung eines Log-Eintrags plattformspezifisch. */
    fun formatLogDefault(level: Level, tag: String, message: String): String

    /** DSL-Typ für [configure]. Auf JS/iOS derzeit nur Platzhalter. */
    class LoggerDsl internal constructor() {
        // Ziele
        fun logToConsole()
        fun logToFile(path: String)
        fun logToCustom(block: (level: Level, tag: String, message: String) -> Unit)
        fun logToCachedForwarding(
            cacheDirPath: String,
            target: (level: Level, tag: String, message: String) -> Unit,
            maxFlushPerCall: Int = 10
        )

        // Filter/Flags
        var minLevel: Level
        var debug: Boolean
    }
}

/**
 * Standard-Tag: einfacher Klassenname der aufrufenden Instanz.
 */
val Any.DEFAULT_LOG_TAG: String
    get() = this::class.simpleName ?: "Unknown"

/**
 * Nur für statische Kontexte ohne Objektinstanz (z. B. top-level).
 */
fun staticDebugLog(level: Logger.Level, tag: String, msg: () -> String) {
    when (level) {
        Logger.Level.DEBUG -> Logger.debug(tag, msg)
        Logger.Level.INFO -> Logger.info(tag, msg)
        Logger.Level.WARN -> Logger.warn(tag, msg)
        Logger.Level.ERROR -> Logger.error(tag, msg)
        Logger.Level.CRASH -> Logger.crash(tag, msg)
    }
}

inline fun <reified T : Any> T.debugLog(noinline msg: () -> String) = Logger.debug(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.infoLog(noinline msg: () -> String) = Logger.info(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.warnLog(noinline msg: () -> String) = Logger.warn(DEFAULT_LOG_TAG, msg)

inline fun <reified T : Any> T.errorLog(
    noinline msg: () -> String,
    ex: Throwable? = null,
    printStackTrace: Boolean = true,
    sendAsCrash: Boolean = false
) {
    val logBlock: () -> String = {
        val base = msg()
        val suffix = ex?.let {
            "\n" + formatThrowable(it, printStackTrace)
        } ?: ""
        base + suffix
    }
    if (sendAsCrash) Logger.crash(DEFAULT_LOG_TAG, logBlock) else Logger.error(DEFAULT_LOG_TAG, logBlock)
}

inline fun <reified T : Any> T.errorLog(ex: Throwable) = Logger.error(DEFAULT_LOG_TAG) { formatThrowable(ex, true) }

/** Plattformabhängige Darstellung von Throwables. */
expect fun formatThrowable(ex: Throwable, printStackTrace: Boolean): String
