package bayern.kickner.klogger.slf4j

import org.slf4j.MDC
import org.slf4j.helpers.BasicMDCAdapter
import kotlin.concurrent.thread
import kotlin.test.*

class KloggerMdcAdapterTest {

    private val mdc = KloggerMdcAdapter()

    @AfterTest
    fun tearDown() {
        mdc.clear()
        MDC.clear()
    }

    @Test
    fun `SLF4J uses the Klogger MDC adapter`() {
        assertIs<KloggerMdcAdapter>(MDC.getMDCAdapter())
    }

    @Test
    fun `put, get and remove`() {
        mdc.put("a", "1")
        mdc.put("b", "2")
        mdc.put("a", "3")
        mdc.remove("b")
        mdc.remove("missing")

        assertEquals("3", mdc.get("a"))
        assertNull(mdc.get("b"))
        assertNull(mdc.get(null))
        assertEquals<Map<String, String?>?>(mapOf("a" to "3"), mdc.copyOfContextMap)
    }

    @Test
    fun `null values are stored like in BasicMDCAdapter`() {
        mdc.put("x", null)

        assertNull(mdc.get("x"))
        assertEquals<Map<String, String?>?>(mapOf("x" to null), mdc.copyOfContextMap)
    }

    @Test
    fun `put with null key throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { mdc.put(null, "x") }
        assertFailsWith<IllegalArgumentException> { BasicMDCAdapter().put(null, "x") } // same contract
    }

    @Test
    fun `context map is null when never set or cleared, empty after removing the last key`() {
        assertNull(mdc.copyOfContextMap)

        mdc.put("a", "1")
        mdc.remove("a")
        assertEquals<Map<String, String?>?>(emptyMap(), mdc.copyOfContextMap)

        mdc.put("a", "1")
        mdc.clear()
        assertNull(mdc.copyOfContextMap)
        assertNull(mdc.get("a"))
    }

    @Test
    fun `setContextMap keeps a copy`() {
        val original = hashMapOf<String, String?>("a" to "1")
        mdc.setContextMap(original)
        original["a"] = "changed"
        original["b"] = "2"

        assertEquals<Map<String, String?>?>(mapOf("a" to "1"), mdc.copyOfContextMap)

        mdc.setContextMap(null)
        assertNull(mdc.copyOfContextMap)
    }

    @Test
    fun `getCopyOfContextMap returns a copy`() {
        mdc.put("a", "1")
        mdc.copyOfContextMap!!["a"] = "changed"

        assertEquals("1", mdc.get("a"))
    }

    @Test
    fun `values are per thread`() {
        mdc.put("a", "main")
        var other: String? = "not run"
        thread {
            mdc.put("a", "other")
            other = mdc.get("a")
        }.join()

        assertEquals("other", other)
        assertEquals("main", mdc.get("a"))
    }

    @Test
    fun `deque operations behave like BasicMDCAdapter`() {
        // Only inline lambdas here: a method with an SLF4J type in its signature (local function, lambda capturing
        // an adapter) breaks test discovery in the testWithoutSlf4j task, which scans this class too.
        val (expected, actual) = listOf(BasicMDCAdapter(), mdc).map { adapter ->
            listOf(
                attempt { adapter.getCopyOfDequeByKey("k") },
                attempt { adapter.popByKey("k") },
                attempt { adapter.pushByKey("k", "a") },
                attempt { adapter.pushByKey("k", "b") },
                attempt { adapter.pushByKey(null, "ignored") },
                attempt { adapter.getCopyOfDequeByKey("k")?.toList() },
                attempt { adapter.popByKey("k") },
                attempt { adapter.getCopyOfDequeByKey("k")?.toList() },
                attempt { adapter.getCopyOfDequeByKey(null) },
                attempt { adapter.popByKey(null) },
                attempt { adapter.clearDequeByKey("k") },
                attempt { adapter.getCopyOfDequeByKey("k")?.toList() },
                attempt { adapter.popByKey("k") },
                attempt { adapter.clearDequeByKey("missing") },
                attempt { adapter.clearDequeByKey(null) },
            )
        }

        assertEquals(expected, actual)
        // sanity check of the reference behaviour
        assertEquals(listOf("b", "a"), expected[5])
        assertEquals(NoSuchElementException::class, expected[12]) // SLF4J's popByKey on an emptied deque throws
    }

    /** The result of [step], or the class of the exception it threw. */
    private inline fun attempt(step: () -> Any?): Any? = runCatching(step).getOrElse { it::class }

    @Test
    fun `deques are not inherited by new threads`() {
        mdc.pushByKey("k", "a")
        var seen: Any? = "not run"

        thread { seen = mdc.getCopyOfDequeByKey("k") }.join()

        assertNull(seen)
        mdc.clearDequeByKey("k")
    }
}
