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

/**
 * Logs a WARN message with an exception, e.g. `warnLog(e) { "Retry $attempt failed" }`.
 *
 * Like the other helpers, [msg] is only evaluated if WARN passes the level filter.
 *
 * @param ex The exception to append to the message.
 * @param printStackTrace If true (default), the full stack trace is appended, otherwise only
 *   `Exception: <message or class name>`.
 */
inline fun <reified T : Any> T.warnLog(
    ex: Throwable,
    printStackTrace: Boolean = true,
    noinline msg: () -> String,
) = KLogger.warn(DEFAULT_LOG_TAG) { withThrowable(msg(), ex, printStackTrace) }

//Callback nicht notwendig, da errors idR immer ausgegeben werden.
inline fun <reified T : Any> T.errorLog(
    msg: String,
    ex: Throwable? = null,
    printStackTrace: Boolean = true,
    sendAsCrash: Boolean = false
) {
    val logBlock: () -> String = { withThrowable(msg, ex, printStackTrace) }
    if (sendAsCrash) KLogger.crash(DEFAULT_LOG_TAG, logBlock) else KLogger.error(DEFAULT_LOG_TAG, logBlock)
}

inline fun <reified T : Any> T.errorLog(ex: Throwable) = KLogger.error(DEFAULT_LOG_TAG) { ex.stackTraceToString() }

/**
 * Appends [ex] to [msg] on a new line: the full stack trace, or only `Exception: <message or class name>` when
 * [printStackTrace] is false. Returns [msg] unchanged if [ex] is null. Shared by [errorLog] and [warnLog].
 */
@PublishedApi
internal fun withThrowable(msg: String, ex: Throwable?, printStackTrace: Boolean): String {
    if (ex == null) return msg
    return msg + "\n" + if (printStackTrace) ex.stackTraceToString() else "Exception: " + (ex.message
        ?: ex::class.simpleName)
}
