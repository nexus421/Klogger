# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Klogger is a lightweight Kotlin logging library (JVM) published as a Gradle artifact (`bayern.kickner:Klogger`). It
provides a singleton `KLogger` with a DSL for configuring multiple simultaneous log destinations (console, file, custom
lambda, cached forwarding, Loki, generic HTTP).

## Commands

Build:

```bash
./gradlew build
```

Run all tests:

```bash
./gradlew test
```

Run a single test class:

```bash
./gradlew test --tests "bayern.kickner.klogger.KLoggerTest"
```

Run a single test method:

```bash
./gradlew test --tests "bayern.kickner.klogger.KLoggerTest.messages below minLevel are not dispatched"
```

Publish to the configured Maven repo (`nexus421Maven`, requires credentials):

```bash
./gradlew publish
```

Requires JDK 17+ (toolchain pinned to 17 in `build.gradle.kts`; `jitpack.yml` builds with JDK 21).

## Architecture

Everything lives under `bayern.kickner.klogger`. There is one core module plus two optional appender modules in
subpackages.

- **`KLogger`** ([KLogger.kt](src/main/kotlin/bayern/kickner/klogger/KLogger.kt)) — the singleton entry point. Holds an
  internal `@Volatile Config` (destinations list + `debug`/`minLevel` flags) that is swapped atomically on
  `configure()`. `configure()` is `@Synchronized` and copies the existing destination list so repeated calls
  *accumulate* destinations rather than replacing them. The `log()` dispatcher checks level filtering, evaluates the
  message lambda only if not filtered, then calls every destination and swallows destination exceptions with
  `runCatching` so a broken destination never breaks the app.
- **`LoggerDsl`** ([LoggerDsl.kt](src/main/kotlin/bayern/kickner/klogger/LoggerDsl.kt)) — the receiver passed into
  `KLogger.configure { }`. Each `logTo*` function appends a `Destination` to the config. The Loki and HTTP appenders add
  their own `logToLoki`/`logToHttp` extension functions onto this same class from their subpackages, so a new appender
  module should follow that pattern (extension function on `LoggerDsl` + own subpackage) instead of modifying
  `LoggerDsl` directly.
- **`Destinations.kt`** — the `Destination` interface and built-in implementations: `ConsoleDestination` (ERROR/CRASH →
  stderr, else stdout), `FileDestination` (`@Synchronized` append), `LambdaDestination` (wraps a user lambda), and the
  cached-forwarding pair `FileBackedLogQueue` (one file per queued entry, atomic write via temp-file rename) +
  `CachedForwardingDestination` (tries the target first, falls back to queuing on failure, and opportunistically flushes
  queued entries — up to `maxFlushPerCall` — on every subsequent log call; stops flushing at the first failure to avoid
  busy-looping).
- **`Statics.kt`** — top-level/extension logging helpers (`debugLog`, `infoLog`, `warnLog`, `errorLog`, `staticLog`)
  that default the tag to `DEFAULT_LOG_TAG` (the receiver's simple class name) so call sites don't need to pass a tag
  explicitly.
- **`loki/LokiAppender.kt`** — batched, non-blocking Loki (Grafana) HTTP appender. Design points worth preserving when
  touching this file: log entries go into a `Channel` via non-blocking `trySend` with `DROP_OLDEST` overflow; a
  background coroutine (`start`/`flush`) drains and POSTs batches on `Dispatchers.IO`; timestamps are forced strictly
  monotonic via `AtomicLong.updateAndGet` because Loki requires unique nanosecond timestamps per stream; `contextFields`
  is a mutable snapshot-per-entry map for dynamic per-request labels; all send failures are caught and logged, never
  thrown, so Loki being down never affects the host app.
- **`http/HttpLogAppender.kt`** — a generic, protocol-agnostic version of the same batching/flush pattern (channel +
  periodic coroutine flush + monotonic timestamps), but the wire format is fully caller-controlled via a
  `bodyBuilder: (List<LogEntry>) -> String` lambda instead of a hardcoded JSON shape. Prefer extending this appender (or
  adding a new `bodyBuilder`) over hardcoding a new backend-specific format, unless the backend needs Loki-specific
  stream/label semantics.

### Cross-cutting conventions

- Every public config surface is a DSL block (`KLogger.configure { ... }`); avoid adding constructor parameters or
  global mutable setters as an alternative configuration path.
- Destinations and appenders must never throw out of their `log`/flush path — failures are caught and reported via
  `errorLog`/`runCatching`, never propagated, since logging must not be able to crash the host application.
- Appender state that's mutated concurrently (queues, timestamps, config swaps) uses `@Volatile`, `AtomicLong`,
  `CopyOnWriteArrayList`, or `Channel` rather than external locking — follow the same primitives when adding new shared
  mutable state.
- Message lambdas (`msg: () -> String`) must stay lazy — they're only invoked after level filtering so filtered-out logs
  never pay for string construction.
