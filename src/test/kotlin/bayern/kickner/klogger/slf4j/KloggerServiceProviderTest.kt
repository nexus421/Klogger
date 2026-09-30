package bayern.kickner.klogger.slf4j

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.*
import kotlin.test.*

class KloggerServiceProviderTest {

    @Test
    fun `service file registers exactly the Klogger provider`() {
        val providers = ServiceLoader.load(SLF4JServiceProvider::class.java).toList()

        assertEquals(listOf(KloggerServiceProvider::class), providers.map { it::class })
    }

    @Test
    fun `SLF4J binds the Klogger logger factory and MDC adapter`() {
        assertIs<KloggerLoggerFactory>(LoggerFactory.getILoggerFactory())
        assertIs<KloggerSlf4jLogger>(LoggerFactory.getLogger("x.Y"))
        assertIs<BasicMDCAdapter>(MDC.getMDCAdapter())
    }

    @Test
    fun `MDC adapter is usable before initialize`() {
        // SLF4J >= 2.0.17 asks for the MDC adapter before calling initialize().
        val provider = KloggerServiceProvider()
        val mdc = provider.mdcAdapter
        mdc.put("k", "v")
        assertEquals("v", mdc.get("k"))
        mdc.clear()

        provider.initialize()
        assertIs<KloggerLoggerFactory>(provider.loggerFactory)
        assertNotNull(provider.markerFactory)
    }

    @Test
    fun `requested API version is accepted by SLF4J 2_0`() {
        assertTrue(KloggerServiceProvider().requestedApiVersion.startsWith("2.0."))
    }

    @Test
    fun `factory returns one cached logger per name`() {
        val factory = KloggerLoggerFactory()

        assertSame(factory.getLogger("a.B"), factory.getLogger("a.B"))
        assertEquals("a.B", factory.getLogger("a.B").name)
    }
}
