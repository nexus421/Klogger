package bayern.kickner.klogger

actual fun formatThrowable(ex: Throwable, printStackTrace: Boolean): String {
    return if (printStackTrace) ex.stackTraceToString()
    else (ex.message ?: ex::class.simpleName ?: ex.toString())
}
