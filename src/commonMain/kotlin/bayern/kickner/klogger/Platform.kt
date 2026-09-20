package bayern.kickner.klogger

/**
 * Platform abstraction for writing error output to standard error.
 */
internal expect fun printStderr(message: String)

/**
 * Reentrant or mutual-exclusion lock for synchronizing state mutations across threads.
 */
internal expect class Lock() {
    inline fun <T> withLock(block: () -> T): T
}
