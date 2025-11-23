package bayern.kickner.klogger

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

actual object Logger {
    actual enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    /** Ziel (Destination) für Log-Ausgaben. */
    internal interface Destination {
        fun log(level: Level, tag: String, message: String)
    }

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    actual fun formatLogDefault(level: Level, tag: String, message: String): String {
        val ts = LocalDateTime.now().format(DATE_FMT)
        return "$ts ${level.name}/$tag: $message"
    }

    private class ConsoleDestination : Destination {
        override fun log(level: Level, tag: String, message: String) {
            if (level == Level.ERROR || level == Level.CRASH) System.err.println(formatLogDefault(level, tag, message))
            else println(formatLogDefault(level, tag, message))
        }
    }

    private class FileDestination(private val file: File) : Destination {
        init {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
        }

        @Synchronized
        override fun log(level: Level, tag: String, message: String) {
            file.appendText(formatLogDefault(level, tag, message) + System.lineSeparator())
        }
    }

    private class LambdaDestination(private val block: (level: Level, tag: String, message: String) -> Unit) :
        Destination {
        override fun log(level: Level, tag: String, message: String) = block(level, tag, message)
    }

    /** Sehr einfache dateibasierte Queue (eine Datei pro Eintrag). */
    private class FileBackedLogQueue(private val dir: File) {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)

        init {
            dir.mkdirs()
        }

        fun isEmpty() = dir.list()?.isEmpty() ?: true

        @Synchronized
        fun enqueue(level: Level, tag: String, message: String) {
            val name = System.currentTimeMillis().toString() + "_" + counter.getAndIncrement() + ".logq"
            val file = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(level.name + "\n" + tag + "\n" + message)
            tmp.renameTo(file)
        }

        @Synchronized
        fun listFiles(): List<File> =
            dir.listFiles { f -> f.isFile && f.name.endsWith(".logq") }?.sortedBy { it.name } ?: emptyList()

        @Synchronized
        fun read(file: File): Triple<Level, String, String> {
            val lines = file.readLines()
            val lvl = Level.valueOf(lines.first())
            val tag = if (lines.size >= 2) lines[1] else ""
            val msg = if (lines.size >= 3) lines.drop(2).joinToString("\n") else ""
            return Triple(lvl, tag, msg)
        }

        @Synchronized
        fun remove(file: File) {
            file.delete()
        }
    }

    /**
     * Leitet Logs weiter; bei Fehlern wird zwischengespeichert und bei späteren Logs gespült.
     */
    private class CachedForwardingDestination(
        private val target: Destination,
        private val queue: FileBackedLogQueue,
        private val maxFlushPerCall: Int = 10,
    ) : Destination {
        override fun log(level: Level, tag: String, message: String) {
            val forwarded = try {
                target.log(level, tag, message)
                true
            } catch (_: Throwable) {
                false
            }
            if (!forwarded) runCatching { queue.enqueue(level, tag, message) }
            tryFlush()
        }

        private fun tryFlush() {
            if (queue.isEmpty()) return
            val files = runCatching { queue.listFiles() }.getOrDefault(emptyList())
            var count = 0
            for (f in files) {
                if (count >= maxFlushPerCall) break
                val (lvl, tg, msg) = try {
                    queue.read(f)
                } catch (_: Throwable) {
                    runCatching { queue.remove(f) }
                    continue
                }
                val ok = try {
                    target.log(lvl, tg, msg)
                    true
                } catch (_: Throwable) {
                    false
                }
                if (ok) {
                    runCatching { queue.remove(f) }
                    count++
                } else break
            }
        }
    }

    internal data class Config(
        val destinations: MutableList<Destination> = CopyOnWriteArrayList(),
        var debug: Boolean = false,
        var minLevel: Level = Level.DEBUG,
    )

    @Volatile
    private var config: Config = Config()

    actual fun configure(block: LoggerDsl.() -> Unit) {
        val dsl = LoggerDsl()
        dsl.cfg = config.copy(destinations = CopyOnWriteArrayList(config.destinations))
        dsl.block()
        config = dsl.build()
    }

    actual fun debug(tag: String, msg: () -> String) = log(Level.DEBUG, tag, msg)
    actual fun info(tag: String, msg: () -> String) = log(Level.INFO, tag, msg)
    actual fun warn(tag: String, msg: () -> String) = log(Level.WARN, tag, msg)
    actual fun error(tag: String, msg: () -> String) = log(Level.ERROR, tag, msg)
    actual fun crash(tag: String, msg: () -> String) = log(Level.CRASH, tag, msg)

    private fun log(level: Level, tag: String, msg: () -> String) {
        val current = config
        if (!current.debug && level.ordinal < current.minLevel.ordinal) return
        val message = msg()
        current.destinations.forEach { dest ->
            runCatching { dest.log(level, tag, message) }
        }
    }

    actual class LoggerDsl internal constructor() {
        internal var cfg: Config? = null

        actual fun logToConsole() {
            cfg!!.destinations.add(ConsoleDestination())
        }

        actual fun logToFile(path: String) {
            cfg!!.destinations.add(FileDestination(File(path)))
        }

        actual fun logToCustom(block: (level: Level, tag: String, message: String) -> Unit) {
            cfg!!.destinations.add(LambdaDestination(block))
        }

        actual fun logToCachedForwarding(
            cacheDirPath: String,
            target: (level: Level, tag: String, message: String) -> Unit,
            maxFlushPerCall: Int
        ) {
            val queue = FileBackedLogQueue(File(cacheDirPath))
            val targetDest = LambdaDestination(target)
            cfg!!.destinations.add(CachedForwardingDestination(targetDest, queue, maxFlushPerCall))
        }

        actual var minLevel: Level
            get() = cfg!!.minLevel
            set(value) {
                cfg!!.minLevel = value
            }

        actual var debug: Boolean
            get() = cfg!!.debug
            set(value) {
                cfg!!.debug = value
            }

        internal fun build(): Config = cfg!!
    }
}
