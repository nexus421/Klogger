package bayern.kickner.klogger.slf4j

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import org.slf4j.*
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MessageFormatter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * SLF4J 2.x provider, registered in `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`.
 *
 * If the application uses SLF4J, directly or through a library such as Ktor, SLF4J picks this provider up
 * automatically and Klogger handles all SLF4J output. Without SLF4J on the classpath this class is never loaded.
 */
class KloggerServiceProvider : SLF4JServiceProvider {
    // Created eagerly: SLF4J >= 2.0.17 calls getMDCAdapter() *before* initialize() (earlyBindMDCAdapter).
    private val loggerFactory: ILoggerFactory = KloggerLoggerFactory()
    private val markerFactory: IMarkerFactory = BasicMarkerFactory()
    private val mdcAdapter: MDCAdapter = BasicMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory
    override fun getMarkerFactory(): IMarkerFactory = markerFactory
    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    /** SLF4J accepts any 2.0.x version. "2.0.99" is the convention used by the reference providers. */
    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() = Unit
}

/** One cached [KloggerSlf4jLogger] per logger name. */
class KloggerLoggerFactory : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()
    override fun getLogger(name: String): Logger = loggers.computeIfAbsent(name) { KloggerSlf4jLogger(it) }
}

/**
 * SLF4J logger that forwards into [KLogger]. [AbstractLogger] already normalizes all overloads
 * (arguments, throwable as last argument, markers, fluent API) into [handleNormalizedLoggingCall].
 *
 * DEBUG, INFO, WARN and ERROR are mapped 1:1. TRACE has no Klogger counterpart and is always dropped.
 * CRASH is never produced. Markers are accepted but ignored.
 */
class KloggerSlf4jLogger internal constructor(name: String) : AbstractLogger() {

    init {
        this.name = name
    }

    /** Klogger tag, derived once from the logger name. */
    private val tag = abbreviateLoggerName(name)

    /** Threshold cached per config instance (config swaps are rare, lookups are hot). */
    private class Resolved(val config: Slf4jBridgeConfig, val threshold: KLogger.Level?)

    @Volatile
    private var resolved: Resolved? = null

    private fun threshold(): KLogger.Level? {
        val cfg = Slf4jBridge.config
        resolved?.let { if (it.config === cfg) return it.threshold }
        return Resolved(cfg, cfg.thresholdFor(name)).also { resolved = it }.threshold
    }

    private fun toKlogger(level: Level): KLogger.Level? = when (level) {
        Level.TRACE -> null
        Level.DEBUG -> KLogger.Level.DEBUG
        Level.INFO -> KLogger.Level.INFO
        Level.WARN -> KLogger.Level.WARN
        Level.ERROR -> KLogger.Level.ERROR
    }

    /** Both the bridge threshold and KLogger's own minLevel must allow [level]. */
    private fun enabled(level: Level): Boolean {
        val threshold = threshold() ?: return false
        val kLevel = toKlogger(level) ?: return false
        return kLevel.ordinal >= threshold.ordinal && KLogger.isEnabled(kLevel)
    }

    override fun isTraceEnabled() = false
    override fun isTraceEnabled(marker: Marker?) = false
    override fun isDebugEnabled() = enabled(Level.DEBUG)
    override fun isDebugEnabled(marker: Marker?) = enabled(Level.DEBUG)
    override fun isInfoEnabled() = enabled(Level.INFO)
    override fun isInfoEnabled(marker: Marker?) = enabled(Level.INFO)
    override fun isWarnEnabled() = enabled(Level.WARN)
    override fun isWarnEnabled(marker: Marker?) = enabled(Level.WARN)
    override fun isErrorEnabled() = enabled(Level.ERROR)
    override fun isErrorEnabled(marker: Marker?) = enabled(Level.ERROR)

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        // A destination that itself logs via SLF4J (e.g. an HTTP client) would otherwise recurse forever.
        if (insideBridge.get()) return
        insideBridge.set(true)
        try {
            // Logging must never break the host app, not even a throwing toString().
            runCatching {
                // Defensive: don't rely on every SLF4J code path having checked isXxxEnabled() first.
                if (!enabled(level)) return
                val kLevel = toKlogger(level) ?: return
                staticLog(kLevel, tag) { format(messagePattern, arguments, throwable) }
            }
        } finally {
            insideBridge.set(false)
        }
    }

    private companion object {
        val insideBridge: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        /** Called lazily by KLogger, only after its own level check passed. Runs on the calling thread. */
        fun format(pattern: String?, args: Array<out Any?>?, throwable: Throwable?): String {
            // log.error("failed for {}", id, ex) with a single-arg overload lands here with ex as argument
            val t = throwable ?: MessageFormatter.getThrowableCandidate(args)
            val cleanArgs = if (throwable == null && t != null) MessageFormatter.trimmedCopy(args) else args
            val text = if (cleanArgs.isNullOrEmpty()) pattern.toString()
            else MessageFormatter.basicArrayFormat(pattern, cleanArgs)

            val mdc = MDC.getCopyOfContextMap()?.takeIf { it.isNotEmpty() }
            if (mdc == null && t == null) return text
            return buildString {
                append(text)
                if (mdc != null) append(' ').append(mdc)
                if (t != null) append('\n').append(t.stackTraceToString())
            }
        }
    }
}
