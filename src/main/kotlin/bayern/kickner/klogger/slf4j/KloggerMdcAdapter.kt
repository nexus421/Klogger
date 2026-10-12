package bayern.kickner.klogger.slf4j

import org.slf4j.helpers.ThreadLocalMapOfStacks
import org.slf4j.spi.MDCAdapter
import java.util.*

/**
 * MDC adapter of the Klogger provider. Like [org.slf4j.helpers.BasicMDCAdapter], but with a plain (not inherited)
 * [ThreadLocal]: threads created while an MDC is set (e.g. pool workers) do NOT inherit it. Otherwise their later
 * log lines would carry a stale context, such as the correlation ID of a request that finished long ago.
 */
class KloggerMdcAdapter : MDCAdapter {
    private val map = ThreadLocal<HashMap<String, String?>>()

    /** SLF4J's own per-thread stacks, also a plain [ThreadLocal]; the same as BasicMDCAdapter uses. */
    private val deques = ThreadLocalMapOfStacks()

    /** @throws IllegalArgumentException if [key] is null, like BasicMDCAdapter. */
    override fun put(key: String?, `val`: String?) {
        requireNotNull(key) { "key cannot be null" }
        (map.get() ?: HashMap<String, String?>().also { map.set(it) })[key] = `val`
    }

    override fun get(key: String?): String? = if (key == null) null else map.get()?.get(key)

    override fun remove(key: String?) {
        map.get()?.remove(key)
    }

    override fun clear() = map.remove()

    /** A copy of this thread's context; `null` if it was never set or was removed by [clear]/`setContextMap(null)`. */
    override fun getCopyOfContextMap(): MutableMap<String, String?>? = map.get()?.let { HashMap(it) }

    /** Stores a copy, so later changes to [contextMap] don't affect the MDC. `null` removes the context. */
    override fun setContextMap(contextMap: MutableMap<String, String?>?) {
        if (contextMap == null) map.remove() else map.set(HashMap(contextMap))
    }

    override fun pushByKey(key: String?, value: String?) = deques.pushByKey(key, value)

    override fun popByKey(key: String?): String? = deques.popByKey(key)

    override fun getCopyOfDequeByKey(key: String?): Deque<String>? = deques.getCopyOfDequeByKey(key)

    override fun clearDequeByKey(key: String?) = deques.clearDequeByKey(key)
}
