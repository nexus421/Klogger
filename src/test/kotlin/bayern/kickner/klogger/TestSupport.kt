package bayern.kickner.klogger

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal HTTP endpoint for appender tests: records every request body and answers with [status].
 * Listens on a random loopback port; [url] is the base URL without trailing slash.
 */
class TestHttpServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val bodies = CopyOnWriteArrayList<String>()

    @Volatile
    var status = 200

    init {
        server.createContext("/") { exchange ->
            bodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** Polls until at least [count] requests arrived; fails the test after [timeoutMs]. */
    fun awaitRequests(count: Int, timeoutMs: Long = 5_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (bodies.size < count) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("expected $count requests within ${timeoutMs}ms, got ${bodies.size}: $bodies")
            }
            Thread.sleep(5)
        }
        return bodies.toList()
    }

    override fun close() = server.stop(0)
}

/** Runs [block] with `System.err` redirected and returns everything it printed. */
fun captureStderr(block: () -> Unit): String {
    val original = System.err
    val buffer = java.io.ByteArrayOutputStream()
    System.setErr(java.io.PrintStream(buffer, true))
    try {
        block()
    } finally {
        System.setErr(original)
    }
    return buffer.toString()
}

/** Polls [condition] until it holds; fails the test with [what] after [timeoutMs]. */
fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
        Thread.sleep(5)
    }
}

/** Redirects `System.err` for the duration of [block]; the buffer is readable while [block] runs. */
fun withCapturedStderr(block: (java.io.ByteArrayOutputStream) -> Unit) {
    val original = System.err
    val buffer = java.io.ByteArrayOutputStream()
    System.setErr(java.io.PrintStream(buffer, true))
    try {
        block(buffer)
    } finally {
        System.setErr(original)
    }
}
