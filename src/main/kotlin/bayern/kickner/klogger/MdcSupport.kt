package bayern.kickner.klogger

/**
 * Reads the SLF4J MDC of the calling thread if slf4j-api is on the classpath.
 * Without SLF4J (e.g. Android without SLF4J) [snapshot] is always empty. Never throws.
 */
internal object MdcSupport {

    /** Checked once. `initialize = false`: only loads the class, without initializing SLF4J. */
    private val available: Boolean = try {
        Class.forName("org.slf4j.MDC", false, MdcSupport::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Snapshot of the MDC, sorted by key, without null keys and null values.
     * Empty if SLF4J is missing, no MDC is set or reading it fails.
     */
    fun snapshot(): Map<String, String> {
        if (!available) return emptyMap()
        return try {
            Slf4jMdcReader.read()
        } catch (_: Throwable) { // also LinkageError/NoClassDefFoundError
            emptyMap()
        }
    }
}

/**
 * The only place that references org.slf4j.MDC directly. A separate object, so the JVM resolves the SLF4J classes
 * only once [MdcSupport] has found them on the classpath.
 */
private object Slf4jMdcReader {
    fun read(): Map<String, String> {
        val raw: Map<String?, String?> = org.slf4j.MDC.getCopyOfContextMap() ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        val result = java.util.TreeMap<String, String>()
        for ((k, v) in raw) if (k != null && v != null) result[k] = v
        return result
    }
}

/**
 * Appends [context] to [message] as ` {k1=v1, k2=v2}`: before the first line break, otherwise at the end.
 * Empty [context] → [message] unchanged (byte-identical to 0.4.0).
 */
internal fun appendContext(message: String, context: Map<String, String>): String {
    if (context.isEmpty()) return message
    val suffix = context.entries.joinToString(", ", prefix = " {", postfix = "}") { "${it.key}=${it.value}" }
    val nl = message.indexOf('\n')
    return if (nl < 0) message + suffix else message.substring(0, nl) + suffix + message.substring(nl)
}
