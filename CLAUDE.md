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
  `runCatching` so a broken destination never breaks the app. `KLogger.flush(timeout)` calls every destination's
  `flush` on a short-lived daemon thread and `join`s it with the timeout; it returns `true` only if the worker finished
  and every destination reported its buffer empty (a throwing flush counts as incomplete): that makes the timeout a hard
  bound even for a
  hung request and keeps network I/O off the caller's thread (Android main thread → `NetworkOnMainThreadException`).
  The optional JVM shutdown hook (`flushOnShutdown`, off by default because Android never runs it) is kept in sync with
  the config by `syncShutdownHook()` at the end of every `configure()`. `Config.format` (default `formatLogDefault`)
  formats console/file lines via `KLogger.format()`, which falls back to the default format if a custom one throws;
  `DEFAULT_FORMAT` must stay declared above `config` because of object initialization order.
- **`LoggerDsl`** ([LoggerDsl.kt](src/main/kotlin/bayern/kickner/klogger/LoggerDsl.kt)) — the receiver passed into
  `KLogger.configure { }`. Each `logTo*` function appends a `Destination` to the config. The Loki and HTTP appenders add
  their own `logToLoki`/`logToHttp` extension functions onto this same class from their subpackages, so a new appender
  module should follow that pattern (extension function on `LoggerDsl` + own subpackage) instead of modifying
  `LoggerDsl` directly. Appenders register one stable `Destination` instance through the internal, identity-deduplicated
  `logTo(destination)` so that re-registering them doesn't deliver every line twice. Per-destination `minLevel` wraps
  built-in destinations in `LevelFilterDestination`; Loki/HTTP keep it as a field checked by their stable destination
  instead, because a wrapper would defeat that deduplication. New parameters on public DSL functions go last (source
  compatibility); `logToCustom` got an overload instead because a parameter in front of the lambda breaks
  `logToCustom(sink)` calls.
- **`Destinations.kt`** — the `Destination` interface (`log` + `flush(timeout): Boolean`, default `true` = nothing
  buffered) and built-in
  implementations: `ConsoleDestination` (ERROR/CRASH → stderr, else stdout), `FileDestination` (`@Synchronized` append,
  optional size rotation `app.log` → `app.log.1` … counted in bytes in memory; rotation stops at the first failed
  delete/rename so no backup is ever overwritten, keeps appending and reports once on stderr), `LevelFilterDestination`,
  `LambdaDestination` (wraps a user lambda), and the
  cached-forwarding pair `FileBackedLogQueue` (one file per queued entry, atomic write via temp-file rename, bounded by
  `maxQueueSize`; `enqueue` throws on persist failure instead of logging) + `CachedForwardingDestination` (on every log
  call it first flushes queued entries oldest-first — up to `maxFlushPerCall`, stopping at the first failure to avoid
  busy-looping — then forwards the current entry directly if no backlog remains, otherwise queues it behind the
  backlog so order is preserved; if the cache itself is unavailable it forwards directly rather than dropping, and
  only when target *and* cache fail is the loss printed to stderr).
- **`Statics.kt`** — top-level/extension logging helpers (`debugLog`, `infoLog`, `warnLog`, `errorLog`, `staticLog`)
  that default the tag to `DEFAULT_LOG_TAG` (the receiver's simple class name) so call sites don't need to pass a tag
  explicitly. Helpers called from these public `inline` functions (`withThrowable`) must be `@PublishedApi internal`.
- **`UncaughtExceptions.kt`** — `KloggerUncaughtExceptionHandler`, installed once by `logUncaughtExceptions()`: logs
  CRASH and flushes on a daemon worker joined with `flushTimeout` (a hanging destination must never keep the crash from
  the previous handler; the dying thread's interrupt flag is cleared for the join and restored, as is an interrupt that
  arrives while waiting), then always delegates to the previously installed default handler (in `finally`). If no
  worker can be started it logs on the dying thread. An `InheritableThreadLocal` marks threads started during crash
  logging; their crashes are only delegated, which breaks crash → log → destination thread → crash loops. Without one it
  prints the JVM's own output itself; never delegate to `ThreadGroup.uncaughtException`, which calls the default
  handler (this one) again and recurses.
- **`loki/LokiAppender.kt`** — batched, non-blocking Loki (Grafana) HTTP appender. Design points worth preserving when
  touching this file: log entries go into a `Channel` via non-blocking `trySend` with `DROP_OLDEST` overflow; a
  background coroutine (`start`/`flush`) drains and POSTs batches on `Dispatchers.IO`; timestamps are forced strictly
  monotonic via `AtomicLong.updateAndGet` because Loki requires unique nanosecond timestamps per stream; `contextFields`
  is a mutable snapshot-per-entry map for dynamic per-request labels; all send failures are caught and reported on
  stderr (never via `errorLog`, which would feed them back into the appender), so Loki being down never affects the
  host app. Sending goes through `sendBatch(timeoutMs)` under a `ReentrantLock` shared by the flush loop (`flush()`) and
  `drain(timeout): Boolean` (called via `KLogger.flush`; sends nothing once the flush loop was cancelled via
  `scope.cancel()`, the documented stop): the lock serializes network I/O so a drain waits for an in-flight batch
  and batches stay in order. It is a deliberate exception to the "no external locking" convention — it guards I/O, not
  state, and `enqueue` never takes it. `drain` caps connect/read timeouts at the remaining time and must never pass 0
  (HttpURLConnection treats 0 as infinite).
- **`http/HttpLogAppender.kt`** — a generic, protocol-agnostic version of the same batching/flush pattern (channel +
  periodic coroutine flush + monotonic timestamps + send lock/`drain`), but the wire format is fully caller-controlled
  via a
  `bodyBuilder: (List<LogEntry>) -> String` lambda instead of a hardcoded JSON shape. Prefer extending this appender (or
  adding a new `bodyBuilder`) over hardcoding a new backend-specific format, unless the backend needs Loki-specific
  stream/label semantics.

- **`slf4j/`** — SLF4J 2.x bridge inside the core artifact. `slf4j-api` is `compileOnly`, so Klogger never pulls SLF4J
  in; `KloggerServiceProvider` is registered via
  `src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider`
  and only loaded when the app already has SLF4J (e.g. via Ktor). Code outside the provider/logger classes (DSL, config,
  `KLogger`) must never reference SLF4J types, otherwise apps without SLF4J break; `./gradlew testWithoutSlf4j` (part of
  `check`) runs `*WithoutSlf4jTest` with slf4j-api removed from the classpath to guard this. `KloggerSlf4jLogger`
  extends SLF4J's `AbstractLogger` and forwards `handleNormalizedLoggingCall` through `staticLog` (tag = abbreviated
  logger name, TRACE always dropped, MDC appended when non-empty). Its settings (`Slf4jBridgeConfig`: own threshold +
  logger-name prefix overrides) live in a `@Volatile` holder set via the `LoggerDsl.slf4jBridge {}` extension; each
  logger caches its resolved threshold per config instance. A `ThreadLocal` guard drops SLF4J calls made from inside a
  destination to prevent recursion. The provider creates its factories eagerly because SLF4J >= 2.0.17 calls
  `getMDCAdapter()` before `initialize()`.

### Cross-cutting conventions

- Every public config surface is a DSL block (`KLogger.configure { ... }`); avoid adding constructor parameters or
  global mutable setters as an alternative configuration path.
- Destinations and appenders must never throw out of their `log`/flush path — failures are caught with `runCatching`,
  never propagated, since logging must not be able to crash the host application. Do not report such failures via
  `errorLog`/`KLogger` from inside a destination: that re-enters the dispatcher (possibly while holding the
  destination's lock) and recurses when the failing destination receives its own error report — use `System.err`.
- Appender state that's mutated concurrently (queues, timestamps, config swaps) uses `@Volatile`, `AtomicLong`,
  `CopyOnWriteArrayList`, or `Channel` rather than external locking — follow the same primitives when adding new shared
  mutable state. (The Loki/HTTP send lock serializes network I/O, not state; see above.)
- New features are opt-in and off by default; without opting in, output must stay byte-identical.
- New DSL members are resolved before members of the user's enclosing class inside `configure { }`, so they shadow
  same-named user properties. Prefer functions (`format(...)`) over properties for new DSL options with common names.
- `ApiCompatibilityTest` pins the 0.3.0 call forms, output and the shadowing cases — if it needs editing, existing
  users break.
- Message lambdas (`msg: () -> String`) must stay lazy — they're only invoked after level filtering so filtered-out logs
  never pay for string construction.
