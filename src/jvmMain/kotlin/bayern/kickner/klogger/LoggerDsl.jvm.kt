package bayern.kickner.klogger

import java.io.File

/**
 * Convenience overload for JVM callers: sets a new file as a logging destination through [FileDestination].
 *
 * @param destinationLogFile The file where log messages will be written.
 */
fun LoggerDsl.logToFile(destinationLogFile: File) {
    logToFile(destinationLogFile.path)
}
