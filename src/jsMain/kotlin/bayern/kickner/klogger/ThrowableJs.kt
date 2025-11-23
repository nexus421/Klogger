package bayern.kickner.klogger

actual fun formatThrowable(ex: Throwable, printStackTrace: Boolean): String {
    // Auf JS gibt es keine echte Stacktrace-Ausgabe wie auf JVM.
    return ex.message ?: (ex::class.simpleName ?: ex.toString())
}
