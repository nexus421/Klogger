# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Klogger is a lightweight Kotlin Multiplatform (KMP) logging library published as a Gradle artifact (`bayern.kickner:Klogger`). It
targets JVM, Apple/iOS (iosArm64, iosSimulatorArm64, iosX64), macOS (macosArm64, macosX64), Linux (linuxX64), and Windows (mingwX64).
It provides a singleton `KLogger` with a DSL for configuring multiple simultaneous log destinations (console, file, custom
lambda, cached forwarding, Loki, generic HTTP).

## Commands

Build:

```bash
./gradlew build
```

Run all tests across host targets:

```bash
./gradlew allTests
```

Run JVM tests:

```bash
./gradlew jvmTest
```

Run Linux native tests (on Linux host):

```bash
./gradlew linuxX64Test
```

Publish to the configured Maven repo (`nexus421Maven`, requires credentials):

```bash
./gradlew publish
```

Publish to local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Requires JDK 17+ (toolchain pinned to 17 in `build.gradle.kts`; `jitpack.yml` builds with JDK 21).

## Architecture

Everything lives under `bayern.kickner.klogger`. There is one core module plus two optional appender modules in
subpackages.

- **`KLogger`** ([KLogger.kt](src/commonMain/kotlin/bayern/kickner/klogger/KLogger.kt)) — the singleton entry point. Holds an
  internal `Config` (destinations list + `debug`/`minLevel` flags) that is swapped atomically on
  `configure()`. `configure()` is synchronized and copies the existing destination list so repeated calls
  *accumulate* destinations rather than replacing them. The `log()` dispatcher checks level filtering, evaluates the
  message lambda only if not filtered, then calls every destination and swallows destination exceptions with
  `runCatching` so a broken destination never breaks the app.
- **`LoggerDsl`** ([LoggerDsl.kt](src/commonMain/kotlin/bayern/kickner/klogger/LoggerDsl.kt)) — the receiver passed into
  `KLogger.configure { }`. Each `logTo*` function appends a `Destination` to the config. The Loki and HTTP appenders add
  their own `logToLoki`/`logToHttp` extension functions onto this same class from their subpackages, so a new appender
  module should follow that pattern (extension function on `LoggerDsl` + own subpackage) instead of modifying
  `LoggerDsl` directly. Appenders register one stable `Destination` instance through the internal, identity-deduplicated
  `logTo(destination)` so that re-registering them doesn't deliver every line twice.
- **`Destinations.kt`** — the `Destination` interface and built-in implementations: `ConsoleDestination` (ERROR/CRASH →
  stderr, else stdout), `FileDestination` (`@Synchronized` append), `LambdaDestination` (wraps a user lambda), and the
  cached-forwarding pair `FileBackedLogQueue` (one file per queued entry, atomic write via temp-file rename, bounded by
  `maxQueueSize`; `enqueue` throws on persist failure instead of logging) + `CachedForwardingDestination` (on every log
  call it first flushes queued entries oldest-first — up to `maxFlushPerCall`, stopping at the first failure to avoid
  busy-looping — then forwards the current entry directly if no backlog remains, otherwise queues it behind the
  backlog so order is preserved; if the cache itself is unavailable it forwards directly rather than dropping, and
  only when target *and* cache fail is the loss printed to stderr).
- **`Statics.kt`** — top-level/extension logging helpers (`debugLog`, `infoLog`, `warnLog`, `errorLog`, `staticLog`)
  that default the tag to `DEFAULT_LOG_TAG` (the receiver's simple class name) so call sites don't need to pass a tag
  explicitly.
- **`loki/LokiAppender.kt`** — batched, non-blocking Loki (Grafana) HTTP appender. Design points worth preserving when
  touching this file: log entries go into a `Channel` via non-blocking `trySend` with `DROP_OLDEST` overflow; a
  background coroutine (`start`/`flush`) drains and POSTs batches on `Dispatchers.IO`; timestamps are forced strictly
  monotonic via `AtomicLong.updateAndGet` because Loki requires unique nanosecond timestamps per stream; `contextFields`
  is a mutable snapshot-per-entry map for dynamic per-request labels; all send failures are caught and reported on
  stderr (never via `errorLog`, which would feed them back into the appender), so Loki being down never affects the
  host app.
- **`http/HttpLogAppender.kt`** — a generic, protocol-agnostic version of the same batching/flush pattern (channel +
  periodic coroutine flush + monotonic timestamps), but the wire format is fully caller-controlled via a
  `bodyBuilder: (List<LogEntry>) -> String` lambda instead of a hardcoded JSON shape. Prefer extending this appender (or
  adding a new `bodyBuilder`) over hardcoding a new backend-specific format, unless the backend needs Loki-specific
  stream/label semantics.

### Cross-cutting conventions

- Every public config surface is a DSL block (`KLogger.configure { ... }`); avoid adding constructor parameters or
  global mutable setters as an alternative configuration path.
- Destinations and appenders must never throw out of their `log`/flush path — failures are caught with `runCatching`,
  never propagated, since logging must not be able to crash the host application. Do not report such failures via
  `errorLog`/`KLogger` from inside a destination: that re-enters the dispatcher (possibly while holding the
  destination's lock) and recurses when the failing destination receives its own error report — use `System.err`.
- Appender state that's mutated concurrently (queues, timestamps, config swaps) uses `@Volatile`, `AtomicLong`,
  `CopyOnWriteArrayList`, or `Channel` rather than external locking — follow the same primitives when adding new shared
  mutable state.
- Message lambdas (`msg: () -> String`) must stay lazy — they're only invoked after level filtering so filtered-out logs
  never pay for string construction.
