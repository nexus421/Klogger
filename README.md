# Klogger

[![Tests](https://github.com/nexus421/Klogger/actions/workflows/tests.yml/badge.svg)](https://github.com/nexus421/Klogger/actions/workflows/tests.yml)

A small Kotlin logging library for the JVM. You write your log calls once and decide in one central place where the
output goes: console, file, Grafana Loki, a generic HTTP endpoint or your own destination. Destinations can be added or
swapped later without changing any log call.

## Why Klogger?

Logging needs change over time: the console during development, a file on a server, a log aggregator such as Grafana
Loki in production. Klogger keeps the log calls in your code independent of where their output ends up.

- **One configuration, several destinations**: All destinations are set in a single `KLogger.configure { ... }` block.
  Switching from console to file or Loki, or writing to several destinations at once, is a configuration change. The
  `debugLog` and `infoLog` calls in your classes stay as they are.
- **Destination errors stay contained**: Exceptions thrown by a destination are caught, so a broken destination breaks
  neither the log call nor the other destinations. `logToCachedForwarding` additionally stores entries on disk while
  its target fails and delivers them on later log calls.
- **Lazy messages**: Messages are passed as lambdas and only evaluated if their level passes the filter, so filtered
  messages don't build their string.
- **Kotlin API**: A small DSL, extension helpers that use the class name as tag, and Loki/HTTP appenders that send in
  batches from a background coroutine.

## Table of contents

- [Why Klogger?](#why-klogger)
- [Installation](#installation)
- [Quick start](#quick-start)
- [API overview](#api-overview)
- [Loki appender](#loki-appender)
- [HTTP appender](#http-appender)
- [Flushing and shutdown](#flushing-and-shutdown)
- [Crash logging](#crash-logging)
- [File rotation](#file-rotation)
- [Per-destination level](#per-destination-level)
- [SLF4J bridge](#slf4j-bridge)
- [Examples](#examples)
- [Formatting](#formatting)
- [Thread-safety](#thread-safety)
- [Limitations](#limitations)
- [Notes](#notes)
- [Upgrading to 0.4.0](#upgrading-to-040)
- [License](#license)

## Installation

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("bayern.kickner:Klogger:0.4.0")
}

repositories {
    mavenCentral()
    maven {
        name = "nexus421MavenReleases"
        url = uri("https://maven.kickner.bayern/releases")
    }
}
```

**Gradle (Groovy):**
```groovy
dependencies {
    implementation "bayern.kickner:Klogger:0.4.0"
}

repositories {
    mavenCentral()
    maven {
        name = "nexus421MavenReleases"
        url = "https://maven.kickner.bayern/releases"
    }
}
```

## Quick start
```kotlin
fun main() {
    KLogger.configure {
        logToConsole()
        minLevel = KLogger.Level.DEBUG   // only messages >= minLevel are processed
        debug = false                    // if true, minLevel is ignored
    }

    class Demo {
        fun run() { debugLog { "Hello from Klogger" } }
    }
    Demo().run()
}
```

**Output:**

```
01.01.2025 14:15:16 DEBUG/Demo: Hello from Klogger
```

## API overview

### `KLogger.configure { ... }`

Configure destinations and flags via DSL. Existing destinations are preserved on subsequent calls.

All `logTo*` functions accept an optional `minLevel` that applies to that destination only (see
[per-destination level](#per-destination-level)). Every option is off unless you set it.

| Method / property                                                                      | Description                                                                                                                                                       |
|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `logToConsole(minLevel = null)`                                                        | DEBUG/INFO/WARN → stdout, ERROR/CRASH → stderr                                                                                                                    |
| `logToFile(File, maxFileSize = null, maxBackupFiles = 3, minLevel = null)`             | Append to file. Parent directories and file are created if missing. Optional size-based rotation (see [file rotation](#file-rotation))                            |
| `logToCustom { level, tag, message -> }`                                               | Custom lambda destination                                                                                                                                         |
| `logToCustom(minLevel) { level, tag, message -> }`                                     | Custom lambda destination that only receives messages at or above `minLevel`                                                                                      |
| `logToCachedForwarding(cacheDirPath, target, maxFlushPerCall, maxQueueSize, minLevel)` | Cache logs to disk if target throws. Retries oldest first on later log calls (up to `maxFlushPerCall`, default 10) and keeps at most `maxQueueSize` (default 500) |
| `logToLoki(...)`                                                                       | Send logs in batches to a Loki server (see [Loki appender](#loki-appender))                                                                                       |
| `logToHttp(appender, minLevel = null)`                                                 | Send logs in batches to any HTTP endpoint (see [HTTP appender](#http-appender))                                                                                   |
| `slf4jBridge { ... }`                                                                  | Tune how SLF4J output is routed into Klogger (see [SLF4J bridge](#slf4j-bridge))                                                                                  |
| `minLevel`                                                                             | Minimum level processed: `DEBUG < INFO < WARN < ERROR < CRASH`                                                                                                    |
| `debug`                                                                                | If `true`, `minLevel` is ignored and all messages are dispatched                                                                                                  |
| `format(lineFormat)`                                                                   | Line format of console and file (see [Formatting](#formatting))                                                                                                   |
| `flushOnShutdown`                                                                      | `null` (default) or a timeout: flush Loki/HTTP buffers in a JVM shutdown hook (see [Flushing and shutdown](#flushing-and-shutdown))                               |
| `logUncaughtExceptions(flushTimeout = 2.seconds)`                                      | Log uncaught exceptions as CRASH and flush (see [Crash logging](#crash-logging))                                                                                  |

Outside the DSL, `KLogger.flush(timeout = 3.seconds)` sends everything the Loki/HTTP appenders still buffer.

### Logging methods (explicit tag)

```kotlin
KLogger.debug(tag) { "message" }
KLogger.info(tag) { "message" }
KLogger.warn(tag) { "message" }
KLogger.error(tag) { "message" }
KLogger.crash(tag) { "message" }
```

### Extension helpers (uses `DEFAULT_LOG_TAG` = simple class name)

```kotlin
debugLog { "message" }
infoLog { "message" }
warnLog { "message" }
warnLog(ex, printStackTrace = true) { "message" }   // message stays lazy
errorLog("message", ex = null, printStackTrace = true, sendAsCrash = false)
errorLog(ex: Throwable)
```

For top-level / static contexts where `DEFAULT_LOG_TAG` is unavailable:

```kotlin
staticLog(KLogger.Level.INFO, "MyTag") { "message" }
```

## Loki appender

Buffers log entries in memory and sends them in batches via HTTP POST to a Loki server.

```kotlin
KLogger.configure {
  logToConsole()
  logToLoki(
    lokiBaseUrl = "http://loki:3100",
    appName = "my-app",
    bearerToken = "secret"
  )
}
```

All parameters with their defaults:

| Parameter       | Default                     | Description                                                          |
|-----------------|-----------------------------|----------------------------------------------------------------------|
| `lokiBaseUrl`   | required                    | Base URL of the Loki server (trailing `/` is stripped automatically) |
| `appName`       | required                    | Value of the `app` label in Loki                                     |
| `bearerToken`   | required                    | `Authorization: Bearer` header value                                 |
| `contextFields` | `emptyMap()`                | Key-value pairs embedded in every log line as JSON fields            |
| `maxQueueSize`  | `500`                       | Buffer capacity. The oldest entry is dropped on overflow             |
| `flushInterval` | `1000 ms`                   | Interval between flushes. Each flush sends one batch                 |
| `batchMaxSize`  | `50`                        | Max entries per HTTP request                                         |
| `scope`         | internal app-lifetime scope | `CoroutineScope` for the flush loop                                  |
| `minLevel`      | `null`                      | Only send messages at or above this level to Loki                    |

With the defaults, the appender sends at most 50 entries per second. If your application logs more than that over a
longer period, the buffer fills up and the oldest entries are dropped. Raise `batchMaxSize` or lower `flushInterval`
in that case. Batches that fail to send (Loki unreachable, non-2xx response) are reported on stderr and dropped, not
retried.

**Context fields** are global for the whole process, not per thread or request:

```kotlin
LokiAppender.contextFields = mapOf("instance" to hostName, "version" to appVersion)
```

They suit values that rarely change. For per-request values such as a call ID in a concurrent server, put them into the
message instead, since concurrent requests would overwrite each other's fields.

**Log line format** sent to Loki:

```json
{
  "level": "INFO",
  "tag": "Handler",
  "message": "...",
  "instance": "web-1",
  "version": "1.4.0"
}
```

To stop sending to Loki: `LokiAppender.scope.cancel()`. Entries logged afterwards are still buffered but no longer sent,
not even by `KLogger.flush()`.
To send the buffer right away, e.g. before exiting, call `KLogger.flush()`
(see [Flushing and shutdown](#flushing-and-shutdown)).

## HTTP appender

A generic variant of the Loki appender for other backends. Buffering and batching work the same way (including the
limits described above), but you decide the request body yourself via `bodyBuilder`.

```kotlin
val appender = HttpLogAppender(
  url = "https://logs.example.com/ingest",
  bodyBuilder = { batch -> batch.joinToString("\n") { "${it.level} ${it.tag}: ${it.message}" } }
).apply {
  addContentType("text/plain")
  addBearer("secret")
}

KLogger.configure {
  logToConsole()
  logToHttp(appender)
}
```

| Parameter              | Default                     | Description                                                                          |
|------------------------|-----------------------------|--------------------------------------------------------------------------------------|
| `url`                  | required                    | Full target URL including path                                                       |
| `bodyBuilder`          | required                    | Turns a batch of `LogEntry` (`timestampNs`, `level`, `tag`, `message`) into the body |
| `maxQueueSize`         | `500`                       | Buffer capacity. The oldest entry is dropped on overflow                             |
| `initialFlushInterval` | `1000 ms`                   | Interval between flushes. Can be changed later via `flushInterval`                   |
| `initialBatchMaxSize`  | `50`                        | Max entries per request. Can be changed later via `batchMaxSize`                     |
| `initialScope`         | internal app-lifetime scope | `CoroutineScope` for the flush loop                                                  |

Headers are set with `addHeader`, `addContentType`, `addContentTypeApplicationJson`, `addBearer` and `addBasicAuth`
and can be changed at runtime, e.g. to rotate a token. The HTTP method defaults to `POST` (`appender.method`). To stop
sending: `appender.scope.cancel()` (afterwards `KLogger.flush()` does not send either).
`logToHttp(appender, minLevel = KLogger.Level.WARN)` sends only WARN and above;
registering the same appender again replaces that filter without duplicating log lines.

## Flushing and shutdown

The Loki and HTTP appenders send in the background once per `flushInterval`. Entries still in their buffer when the
process ends are lost unless they are flushed first. All other destinations write synchronously and need no flush.

```kotlin
val complete: Boolean = KLogger.flush(timeout = 3.seconds)  // false if the time ran out
```

`KLogger.flush` waits at most `timeout`, even if a server hangs, and returns `false` if the buffers could not be emptied
in time. Entries not taken yet stay buffered for the regular flush loop; a batch whose request is cut short by the
timeout is dropped like any failed batch. The work runs on a separate daemon thread, so calling it on the Android main
thread does not cause a `NetworkOnMainThreadException`, but it still blocks the caller for up to `timeout`.

**JVM / server applications:** let a shutdown hook do it. Off by default:

```kotlin
KLogger.configure {
    logToLoki(lokiBaseUrl = "http://loki:3100", appName = "my-app", bearerToken = "secret")
    flushOnShutdown = 3.seconds   // null (default) = no shutdown hook
}
```

The hook runs on a normal exit, `System.exit` and SIGTERM/SIGINT, not on SIGKILL. Setting `flushOnShutdown = null`
later removes it again. Shutdown hooks run concurrently: if your own shutdown code cancels an appender's `scope`, that
appender sends nothing anymore, so keep the scope alive if you rely on `flushOnShutdown`.

**Android:** leave `flushOnShutdown` off. Android apps are killed without a regular VM shutdown, so the hook would
practically never run. Flush from a lifecycle callback instead, off the main thread:

```kotlin
override fun onStop() {
    super.onStop()
    lifecycleScope.launch(Dispatchers.IO) { KLogger.flush() }
}
```

## Crash logging

```kotlin
KLogger.configure {
    logToFile(File("logs/app.log"))
    logUncaughtExceptions(flushTimeout = 2.seconds)
}
```

Every uncaught exception is logged as `CRASH` with tag `UncaughtException`, the thread name and the stack trace. Then
Loki/HTTP buffers are flushed, and the exception is handed to the default handler that was installed before. Logging
and flushing together take at most `flushTimeout`, so even a destination that hangs cannot keep the crash from that
handler. Logging runs on a short-lived helper thread, so with `format(KLogger.defaultFormat(threadName = true))` the
CRASH line shows `[klogger-crash]`; the crashed thread's name is part of the message. If no thread can be started at all
(`OutOfMemoryError: unable to create native thread`), the crash is still logged, on the crashing thread, but not
flushed. A thread that a destination starts while a crash is logged is not logged again if it crashes too, so a broken
destination cannot start an endless crash-log loop. The platform's crash behaviour stays intact: the JVM still prints
the exception, Android still shows
its crash dialog and ends the process, crash reporters such as Crashlytics still see the crash.

Off unless called. The handler is installed once per process; calling `logUncaughtExceptions` again only updates the
timeout. A handler that another library installs later runs first and reaches Klogger's only if it passes the exception
on to the previous handler (crash reporters do). The handler is installed as soon as `logUncaughtExceptions` runs.

## File rotation

```kotlin
KLogger.configure {
    logToFile(File("logs/app.log"), maxFileSize = 5L * 1024 * 1024, maxBackupFiles = 3)
}
```

Before a line would push `app.log` beyond `maxFileSize` bytes, it is renamed to `app.log.1`, `app.log.1` to
`app.log.2` and so on. At most `maxBackupFiles` backups are kept, the oldest is deleted. A single line larger than
`maxFileSize` is still written, into a fresh file. If rotation fails (e.g. the file is locked), Klogger keeps appending
to `app.log` and reports the problem once on stderr. Without `maxFileSize` the file is never rotated. If you lower
`maxBackupFiles` later, backups beyond the new limit are left untouched; delete them yourself if needed. Only one
destination and one process may write a given file.

## Per-destination level

```kotlin
KLogger.configure {
    minLevel = KLogger.Level.DEBUG                       // global filter, applied first
    logToConsole()                                       // everything that passes minLevel
    logToFile(File("logs/app.log"), minLevel = KLogger.Level.INFO)
    logToLoki(lokiBaseUrl = "http://loki:3100", appName = "my-app", bearerToken = "secret",
              minLevel = KLogger.Level.WARN)
    logToCustom(KLogger.Level.ERROR) { level, tag, message -> alerting.send("$level $tag: $message") }
}
```

A destination's `minLevel` filters in addition to the global `minLevel`, and it also applies when `debug = true`. Only
the global `minLevel` prevents the message lambda from being evaluated, so set it to the lowest level any destination
needs.

## SLF4J bridge

Klogger includes an SLF4J 2.x provider. If anything in your application logs via SLF4J, for example Ktor, Netty,
Exposed or HikariCP, Klogger takes over that output automatically and writes it to your configured destinations.
No extra dependency or setup is required. Klogger itself does not add SLF4J to your project.

Optional tuning:

```kotlin
KLogger.configure {
  logToConsole()
  minLevel = KLogger.Level.DEBUG            // your own logs

  slf4jBridge {
    minLevel = KLogger.Level.INFO         // default for all SLF4J loggers (default: INFO)
    level("io.ktor", KLogger.Level.DEBUG) // longest matching prefix wins
    level("io.netty", KLogger.Level.WARN)
    off("com.zaxxer.hikari.pool")         // drop completely
  }
}
```

Behaviour:

- A message is logged only if both the bridge threshold and `KLogger.minLevel` allow it. `debug = true` does not lift
  the bridge threshold.
- The tag is the abbreviated logger name, e.g. `io.ktor.server.Application` becomes `i.k.s.Application`.
- TRACE is always dropped because Klogger has no TRACE level.
- A non-empty MDC (e.g. the `call-id` of Ktor's CallId plugin) is appended to the message as `{call-id=...}`.
- SLF4J 1.7 does not detect the provider. SLF4J 2.x is required.
- If another provider such as logback is on the classpath too, SLF4J prints a warning and uses one of them. Select
  Klogger explicitly with `-Dslf4j.provider=bayern.kickner.klogger.slf4j.KloggerServiceProvider`.

## Examples

**Log to file:**

```kotlin
KLogger.configure {
    logToFile(File("logs/app.log"))
    minLevel = KLogger.Level.INFO
}
```

**Custom destination:**

```kotlin
KLogger.configure {
    logToCustom { level, tag, msg -> println("[$level][$tag] $msg") }
}
```

**Error with exception:**

```kotlin
class Service {
    fun work() {
        try { /*...*/ } catch (e: Throwable) { errorLog("failed", ex = e) }
    }
}
```

**Crash level:**

```kotlin
errorLog("fatal error", sendAsCrash = true)
```

**Cached forwarding (persistent queue, e.g. for an unreliable network):**
```kotlin
var serverOnline = false
KLogger.configure {
    logToCachedForwarding("./logcache", target = { level, tag, message ->
        if (!serverOnline) throw IllegalStateException("Server offline")
        println("SENT: " + KLogger.formatLogDefault(level, tag, message))
    })
}

KLogger.debug("Demo") { "cached while offline" }
serverOnline = true
KLogger.info("Demo") { "now online, triggers cache flush" }
```

The target is called on the logging thread. Cached entries are only retried when the next message is logged, there is
no background retry.

## Formatting

Console and file destinations use this format by default, in local time
(`KLogger.formatLogDefault(level, tag, message)`):

```
dd.MM.yyyy HH:mm:ss LEVEL/TAG: message
```

Change it with `format(...)`, either via `KLogger.defaultFormat` or with your own lambda:

```kotlin
KLogger.configure {
    format(KLogger.defaultFormat(timestampPattern = "dd.MM.yyyy HH:mm:ss.SSS", threadName = true))
    // 08.10.2026 14:03:12.345 [main] INFO/Handler: started

    // or fully custom:
    format { level, tag, message -> "[$level] $tag: $message" }
}
```

An invalid `timestampPattern` fails inside `configure`. If a custom lambda throws, the line is written in the default
format instead. Custom, cached forwarding, Loki and HTTP destinations receive the unformatted message and are not
affected by `format`. The format is kept across later `configure` calls.

## Thread-safety

| Component                     | Guarantee                                                                             |
|-------------------------------|---------------------------------------------------------------------------------------|
| `KLogger.configure()`         | `@Synchronized`, safe for concurrent calls, no lost updates                           |
| Destination list              | `CopyOnWriteArrayList`, safe concurrent reads during dispatch                         |
| Config reference              | `@Volatile`, swapped atomically                                                       |
| `FileDestination`             | `@Synchronized` per instance                                                          |
| Cached forwarding             | `@Synchronized` per instance                                                          |
| Loki / HTTP appender log call | Non-blocking (`Channel.trySend`), timestamps are strictly increasing via `AtomicLong` |
| `LokiAppender.contextFields`  | `@Volatile`, can be replaced from any thread                                          |
| `HttpLogAppender` headers     | `ConcurrentHashMap`, can be changed while the appender is running                     |
| Loki / HTTP sending           | One request at a time: `KLogger.flush` waits for a batch the flush loop is sending    |
| `KLogger.flush()`             | Safe from any thread, runs the flush on its own daemon thread                         |

## Limitations

- Console, file, custom and cached forwarding destinations run synchronously on the calling thread. A slow destination
  slows down the log call.
- `logToFile` rotates by size only, not by date.
- The Loki and HTTP appenders keep their buffer in memory only. Failed batches are dropped, and entries still buffered
  when the process ends are lost unless `KLogger.flush()` or `flushOnShutdown` sent them first. Use
  `logToCachedForwarding` if entries have to survive outages.
- There is no TRACE level.

## Notes

- Message lambdas are **not evaluated** when filtered by `minLevel`, so the message string is never built.
  `errorLog(msg: String, ...)` takes a plain string, only the exception part is built lazily.
- If a message is logged while no destination is configured, a hint is printed once to `stderr`.
- `configure()` **accumulates** destinations across calls. It does not reset existing ones. Calling `logToConsole()` or
  `logToFile(...)` in two `configure` blocks registers them twice, so each line is written twice. The Loki appender and
  the same `HttpLogAppender` instance are only registered once.
- Klogger depends on `kotlinx-coroutines-core` and `kotlinx-serialization-json`, both are pulled in transitively.
  `kotlinx-coroutines-core` is exposed as an `api` dependency, so `CoroutineScope` (e.g. `LokiAppender.scope.cancel()`)
  is available in your code without declaring it yourself.

## Upgrading to 0.4.0

0.4.0 is source compatible with 0.3.x: existing code compiles unchanged and produces the same output. All new features
are opt-in. (One theoretical exception: inside `configure { }` the new DSL property `flushOnShutdown` takes precedence
over a member of the same name in your own class, and the new functions `format(...)` and `logUncaughtExceptions(...)`
over same-named functions whose arguments fit. Write `this@MyClass.flushOnShutdown` in that case.) Several `logTo*`
functions gained an optional `minLevel` parameter, which changes their JVM signature, so
recompile code that calls Klogger (a normal Gradle build does that). A library compiled against 0.3.x would have to be
rebuilt as well.

## License
WTFPL
