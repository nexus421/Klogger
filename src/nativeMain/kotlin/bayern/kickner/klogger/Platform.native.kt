@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bayern.kickner.klogger

import platform.posix.fprintf
import platform.posix.stderr
import platform.posix.fflush
import kotlin.concurrent.AtomicInt

internal actual fun printStderr(message: String) {
    fprintf(stderr, "%s\n", message)
    fflush(stderr)
}

internal actual class Lock actual constructor() {
    private val state = AtomicInt(0)

    actual inline fun <T> withLock(block: () -> T): T {
        while (!state.compareAndSet(0, 1)) {
            // spin-wait
        }
        try {
            return block()
        } finally {
            state.value = 0
        }
    }
}
