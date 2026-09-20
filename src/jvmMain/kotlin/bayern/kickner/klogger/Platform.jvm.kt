package bayern.kickner.klogger

internal actual fun printStderr(message: String) {
    System.err.println(message)
}

internal actual class Lock actual constructor() {
    private val lock = Any()

    actual inline fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}
