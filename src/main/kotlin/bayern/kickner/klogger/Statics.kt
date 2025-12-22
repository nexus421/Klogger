package bayern.kickner.klogger

/**
 * Default log tag: simple class name of the calling instance.
 */
val Any.DEFAULT_LOG_TAG: String
    get() = this::class.simpleName ?: "Unknown"

/**
 * Use this only, if you want to log something within a static file.
 * Within static files, we don't have [Any.DEFAULT_LOG_TAG]
 */
fun staticLog(level: KLogger.Level, tag: String, msg: () -> String) {
    when (level) {
        KLogger.Level.DEBUG -> KLogger.debug(tag, msg)
        KLogger.Level.INFO -> KLogger.info(tag, msg)
        KLogger.Level.WARN -> KLogger.warn(tag, msg)
        KLogger.Level.ERROR -> KLogger.error(tag, msg)
        KLogger.Level.CRASH -> KLogger.crash(tag, msg)
    }
}

inline fun <reified T : Any> T.debugLog(noinline msg: () -> String) = KLogger.debug(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.infoLog(noinline msg: () -> String) = KLogger.info(DEFAULT_LOG_TAG, msg)
inline fun <reified T : Any> T.warnLog(noinline msg: () -> String) = KLogger.warn(DEFAULT_LOG_TAG, msg)

//Callback nicht notwendig, da errors idR immer ausgegeben werden.
inline fun <reified T : Any> T.errorLog(
    msg: String,
    ex: Throwable? = null,
    printStackTrace: Boolean = true,
    sendAsCrash: Boolean = false
) {
    val logBlock: () -> String = {
        val suffix = ex?.let {
            "\n" + if (printStackTrace) it.stackTraceToString() else ("Exception: " + (it.message
                ?: it::class.simpleName))
        } ?: ""
        msg + suffix
    }
    if (sendAsCrash) KLogger.crash(DEFAULT_LOG_TAG, logBlock) else KLogger.error(DEFAULT_LOG_TAG, logBlock)
}

inline fun <reified T : Any> T.errorLog(ex: Throwable) = KLogger.error(DEFAULT_LOG_TAG) { ex.stackTraceToString() }