package bayern.kickner.klogger.slf4j

import org.slf4j.MDC
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

/** Threads created while an MDC is set must not inherit it (regression for SLF4J's InheritableThreadLocal). */
class MdcInheritanceTest {

    @BeforeTest
    fun setUp() = MDC.clear()

    @AfterTest
    fun tearDown() = MDC.clear()

    @Test
    fun `a new thread does not inherit the MDC`() {
        MDC.put("correlationId", "ALT")
        var seen: String? = "not run"

        thread { seen = MDC.get("correlationId") }.join()

        assertNull(seen)
    }

    @Test
    fun `a pool worker created while an MDC is set does not keep it`() {
        val pool = Executors.newFixedThreadPool(1)
        try {
            MDC.put("correlationId", "ALT")
            pool.submit {}.get() // creates the worker thread while the MDC is set
            MDC.clear()

            val seen = pool.submit<String?> { MDC.get("correlationId") }.get()

            assertNull(seen)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
