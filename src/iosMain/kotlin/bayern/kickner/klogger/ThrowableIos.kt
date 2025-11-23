package bayern.kickner.klogger

actual fun formatThrowable(ex: Throwable, printStackTrace: Boolean): String {
    // Auf iOS/Kotlin Native meist kein detailreicher Stacktrace verfügbar
    return ex.message ?: (ex::class.simpleName ?: ex.toString())
}
