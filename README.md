# Klogger

A lightweight Kotlin logging utility with a clean DSL, multiple destinations, and an optional Loki appender.

## Table of contents

- [Installation](#installation)
- [Quick start](#quick-start)
- [API overview](#api-overview)
- [Loki appender](#loki-appender)
- [Examples](#examples)
- [Formatting](#formatting)
- [Thread-safety](#thread-safety)
- [Notes](#notes)
- [License](#license)

## Installation

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("bayern.kickner:Klogger:0.1.0")
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
    implementation "bayern.kickner:Klogger:0.1.0"
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

| Method                                                         | Description                                                                 |
|----------------------------------------------------------------|-----------------------------------------------------------------------------|
| `logToConsole()`                                               | DEBUG/INFO/WARN → stdout, ERROR/CRASH → stderr                              |
| `logToFile(File)`                                              | Append to file; parent directories and file are created if missing          |
| `logToCustom { level, tag, message -> }`                       | Custom lambda destination                                                   |
| `logToCachedForwarding(cacheDirPath, target, maxFlushPerCall)` | Cache logs to disk if target throws; flush on subsequent log calls          |
| `logToLoki(...)`                                               | Send logs in batches to a Loki server (see [Loki appender](#loki-appender)) |
| `minLevel`                                                     | Minimum level processed: `DEBUG < INFO < WARN < ERROR < CRASH`              |
| `debug`                                                        | If `true`, `minLevel` is ignored and all messages are dispatched            |

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
errorLog("message", ex = null, printStackTrace = true, sendAsCrash = false)
errorLog(ex: Throwable)
```

For top-level / static contexts where `DEFAULT_LOG_TAG` is unavailable:

```kotlin
staticLog(KLogger.Level.INFO, "MyTag") { "message" }
```

## Loki appender

Buffers log entries and sends them in batches via HTTP POST to a Loki server.

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
| `lokiBaseUrl`   | –                           | Base URL of the Loki server (trailing `/` is stripped automatically) |
| `appName`       | –                           | Value of the `app` label in Loki                                     |
| `bearerToken`   | –                           | `Authorization: Bearer` header value                                 |
| `contextFields` | `emptyMap()`                | Key-value pairs embedded in every log line as JSON fields            |
| `maxQueueSize`  | `500`                       | Buffer capacity; oldest entry is dropped on overflow                 |
| `flushInterval` | `1000 ms`                   | Interval between batch flushes                                       |
| `batchMaxSize`  | `50`                        | Max entries per HTTP request                                         |
| `scope`         | internal app-lifetime scope | `CoroutineScope` for the flush loop                                  |

**Dynamic context fields** (e.g. per-request tracing):

```kotlin
LokiAppender.contextFields = mapOf("callId" to id, "tenant" to name)
```

**Log line format** sent to Loki:

```json
{
  "level": "INFO",
  "tag": "Handler",
  "message": "...",
  "callId": "123",
  "tenant": "Foo"
}
```

To stop logging: `LokiAppender.scope.cancel()`

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

**Cached forwarding (persistent queue, e.g. for unreliable network):**
```kotlin
var serverOnline = false
KLogger.configure {
    logToCachedForwarding("./logcache", target = { level, tag, message ->
        if (!serverOnline) throw IllegalStateException("Server offline")
        println("SENT: " + KLogger.formatLogDefault(level, tag, message))
    })
}

debugLog { "cached while offline" }
serverOnline = true
infoLog { "now online – triggers cache flush" }
```

## Formatting

Default format produced by `KLogger.formatLogDefault(level, tag, message)`:

```
dd.MM.yyyy HH:mm:ss LEVEL/TAG: message
```

## Thread-safety

| Component                | Guarantee                                                                         |
|--------------------------|-----------------------------------------------------------------------------------|
| `KLogger.configure()`    | `@Synchronized` – safe for concurrent calls, no lost updates                      |
| Destination list         | `CopyOnWriteArrayList` – safe concurrent reads during dispatch                    |
| `FileDestination`        | `@Synchronized` per instance                                                      |
| Config reference         | `@Volatile` – swapped atomically                                                  |
| `LokiAppender.enqueue()` | Lock-free (`Channel.trySend`); timestamps are strictly monotonic via `AtomicLong` |
| `LokiAppender` fields    | `@Volatile` throughout                                                            |

## Notes

- Message lambdas are **not evaluated** when filtered by `minLevel` (no unnecessary string allocation).
- A warning is printed to `stderr` once if logging is used before `configure()` is called.
- `configure()` **accumulates** destinations across calls; it does not reset existing ones.
- The Loki appender requires `kotlinx-coroutines-core` and `kotlinx-serialization-json` (both are transitive
  dependencies of this library).

## License
WTFPL
