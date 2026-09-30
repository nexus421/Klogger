package bayern.kickner.klogger.slf4j

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.LoggerDsl

/**
 * Immutable settings of the SLF4J bridge. Swapped atomically via [Slf4jBridge.config].
 * Every [KloggerSlf4jLogger] re-resolves its threshold when it sees a new instance.
 *
 * @property minLevel Default threshold for all SLF4J loggers. It is independent of the minLevel of [KLogger],
 *   so Netty or Ktor internals stay quiet even if the app itself logs at DEBUG.
 * @property overrides Logger name prefix mapped to a threshold. `null` switches the prefix off completely.
 *   The longest matching prefix wins. A prefix matches the exact name and everything below it, so `"io.netty"`
 *   matches `io.netty.channel.X` but not `io.nettyfoo`.
 */
class Slf4jBridgeConfig(
    val minLevel: KLogger.Level = KLogger.Level.INFO,
    val overrides: Map<String, KLogger.Level?> = emptyMap(),
) {
    /** Threshold for [loggerName]. `null` means off. */
    internal fun thresholdFor(loggerName: String): KLogger.Level? {
        var best: String? = null
        for (prefix in overrides.keys) {
            val matches = loggerName == prefix || loggerName.startsWith("$prefix.")
            if (matches && (best == null || prefix.length > best.length)) best = prefix
        }
        return if (best != null) overrides[best] else minLevel
    }
}

/** Global holder, read by every bridged logger on each call (single volatile read). */
object Slf4jBridge {
    @Volatile
    var config: Slf4jBridgeConfig = Slf4jBridgeConfig()
        internal set
}

/** DSL receiver for [slf4jBridge]. */
class Slf4jBridgeDsl internal constructor() {
    /** See [Slf4jBridgeConfig.minLevel]. Default: INFO. */
    var minLevel: KLogger.Level = KLogger.Level.INFO

    private val overrides = LinkedHashMap<String, KLogger.Level?>()

    /** Use [level] as threshold for all loggers named [prefix] or below it. */
    fun level(prefix: String, level: KLogger.Level) {
        overrides[prefix] = level
    }

    /** Drop everything from loggers named [prefix] or below it. */
    fun off(prefix: String) {
        overrides[prefix] = null
    }

    internal fun build() = Slf4jBridgeConfig(minLevel, overrides.toMap())
}

/**
 * Configures how SLF4J output from libraries such as Ktor, Netty, Exposed or HikariCP is routed into Klogger.
 *
 * No setup is needed to activate the bridge. If anything in the application logs via SLF4J 2.x, SLF4J finds the
 * Klogger provider on its own and all that output goes to the configured Klogger destinations. This block only
 * tunes the bridge. Calling it again replaces the previous bridge settings instead of merging them.
 *
 * ```
 * KLogger.configure {
 *     logToConsole()
 *     slf4jBridge {
 *         minLevel = KLogger.Level.INFO
 *         level("io.ktor", KLogger.Level.DEBUG)
 *         level("io.netty", KLogger.Level.WARN)
 *         off("com.zaxxer.hikari.pool")
 *     }
 * }
 * ```
 */
@Suppress("UnusedReceiverParameter") // lives on LoggerDsl for discoverability, like logToLoki/logToHttp
fun LoggerDsl.slf4jBridge(block: Slf4jBridgeDsl.() -> Unit = {}) {
    Slf4jBridge.config = Slf4jBridgeDsl().apply(block).build()
}

/** Klogger tag for an SLF4J logger name: `io.ktor.server.Application` → `i.k.s.Application`. */
internal fun abbreviateLoggerName(name: String): String {
    val lastDot = name.lastIndexOf('.')
    if (lastDot <= 0) return name
    return name.substring(0, lastDot).split('.')
        .joinToString(".", postfix = ".") { it.take(1) } + name.substring(lastDot + 1)
}
