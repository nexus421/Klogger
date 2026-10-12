package bayern.kickner.klogger

import org.slf4j.MDC
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MdcSupportTest {

    @BeforeTest
    fun setUp() = MDC.clear()

    @AfterTest
    fun tearDown() = MDC.clear()

    @Test
    fun `snapshot without MDC is empty`() {
        assertEquals(emptyMap(), MdcSupport.snapshot())
    }

    @Test
    fun `snapshot is sorted by key`() {
        MDC.put("b", "2")
        MDC.put("a", "1")

        val snapshot = MdcSupport.snapshot()

        assertEquals(listOf("a" to "1", "b" to "2"), snapshot.toList())
    }

    @Test
    fun `snapshot skips null values`() {
        MDC.put("x", null)
        MDC.put("y", "1")

        assertEquals(mapOf("y" to "1"), MdcSupport.snapshot())
    }

    @Test
    fun `snapshot is a copy`() {
        MDC.put("a", "1")
        val snapshot = MdcSupport.snapshot()
        MDC.put("a", "2")

        assertEquals(mapOf("a" to "1"), snapshot)
    }

    @Test
    fun `appendContext leaves the message unchanged without context`() {
        assertEquals("msg", appendContext("msg", emptyMap()))
        assertEquals("l1\nl2", appendContext("l1\nl2", emptyMap()))
    }

    @Test
    fun `appendContext appends the context in map order`() {
        assertEquals("msg {a=1, b=2}", appendContext("msg", linkedMapOf("a" to "1", "b" to "2")))
        assertEquals(" {a=1}", appendContext("", mapOf("a" to "1")))
    }

    @Test
    fun `appendContext inserts the context before the first line break`() {
        assertEquals("l1 {a=1}\nl2", appendContext("l1\nl2", mapOf("a" to "1")))
        assertEquals("l1 {a=1}\nl2\nl3", appendContext("l1\nl2\nl3", mapOf("a" to "1")))
        assertEquals(" {a=1}\nl2", appendContext("\nl2", mapOf("a" to "1")))
    }
}
