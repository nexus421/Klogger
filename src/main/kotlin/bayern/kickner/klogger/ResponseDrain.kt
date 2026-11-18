package bayern.kickner.klogger

import java.io.InputStream
import java.net.HttpURLConnection

/** The response body of a log push is irrelevant; at most this many bytes of it are read. */
private const val MAX_DISCARDED_RESPONSE_BYTES = 8 * 1024

/**
 * Reads and discards the response body [body] of a log push, but at most [MAX_DISCARDED_RESPONSE_BYTES] bytes and
 * for at most [budgetMs] (checked between reads; a single read is bounded by the connection's read timeout).
 *
 * A body that ends within these limits is consumed completely, so the JVM can reuse the TCP connection (HTTP
 * keep-alive). Otherwise the connection is closed instead of buffering a huge or endless response in memory or
 * waiting for a trickling one while the appender's send lock is held.
 *
 * Throws like the underlying stream (e.g. [java.net.SocketTimeoutException]); the callers report that on stderr.
 */
internal fun HttpURLConnection.discardResponseBody(body: InputStream?, budgetMs: Int) {
    if (body == null) return
    val deadline = System.nanoTime() + budgetMs * 1_000_000L
    var complete = false
    try {
        val buffer = ByteArray(1024)
        var left = MAX_DISCARDED_RESPONSE_BYTES
        while (left > 0 && System.nanoTime() - deadline < 0) {
            val read = body.read(buffer, 0, minOf(buffer.size, left))
            if (read < 0) {
                complete = true
                break
            }
            left -= read
        }
    } finally {
        // Before close(): closing a partly read stream may try to read the rest of it
        if (!complete) disconnect()
        runCatching { body.close() }
    }
}
