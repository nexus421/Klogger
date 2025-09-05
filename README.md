# Klogger

A tiny, zero-dependency Kotlin logging utility with a clean DSL.

## Badges
- Kotlin: JVM

## Table of contents
- Installation
- Quick start
- API overview
- Examples
- Formatting
- Thread-safety
- Notes
- License

## Installation
- Gradle (Kotlin DSL):
  - not yet
- Gradle (Groovy):
  - not yet

## Quick start
```kotlin
fun main() {
    Logger.configure {
        logToConsole()
        // logToFile("logs/app.log")
        // logToCustom { level, tag, message -> println("CUSTOM $level/$tag: $message") }
        minLevel = Logger.Level.DEBUG   // only messages >= minLevel are processed
        debug = false                   // if true, minLevel is ignored
    }

    // Uses DEFAULT_LOG_TAG (caller simple class name)
    class Demo { fun run() { debugLog { "Hello from Klogger" } } }
    Demo().run()
}
```
## Example output
- 01.01.2025 14:15:16 DEBUG/Demo: Hello from Klogger

## API overview
- Logger.configure { ... }: Configure destinations and flags via DSL.
  - logToConsole(): Console destination. DEBUG/INFO/WARN -> stdout, ERROR/CRASH -> stderr.
  - logToFile(path: String): Append to a file. Parent directories are created; file is created if missing.
  - logToCustom { level, tag, message -> ... }: Add a custom lambda destination.
  - minLevel: Minimum level processed (DEBUG < INFO < WARN < ERROR < CRASH).
  - debug: If true, minLevel is ignored and all messages are processed.
- Logging methods with explicit tag:
  - Logger.debug(tag, msg: () -> String)
  - Logger.info(tag, msg: () -> String)
  - Logger.warn(tag, msg: () -> String)
  - Logger.error(tag, msg: () -> String)
  - Logger.crash(tag, msg: () -> String)
- Extension helpers using DEFAULT_LOG_TAG (non-static contexts):
  - (Any.)debugLog { ... }
  - (Any.)infoLog { ... }
  - (Any.)warnLog { ... }
  - (Any.)errorLog(msg: () -> String, ex: Throwable? = null, printStackTrace: Boolean = true, sendAsCrash: Boolean = false)
  - (Any.)errorLog(ex: Throwable)

## Examples
- Log to file
  - Logger.configure { logToFile("logs/app.log"); minLevel = Logger.Level.INFO }
- Custom destination
  - Logger.configure { logToCustom { level, tag, msg -> println("[$level][$tag] $msg") } }
- Error with exception
  - class Service { fun work() { try { /*...*/ } catch (e: Throwable) { errorLog({ "failed" }, e) } } }
- Crash level
  - errorLog({ "fatal" }, sendAsCrash = true)

## Formatting
- Default log format by formatLogDefault(level, tag, message):
  - dd.MM.yyyy HH:mm:ss LEVEL/TAG: message

Thread-safety
- Destinations use CopyOnWriteArrayList for concurrent reads/writes.
- File writes are synchronized per FileDestination instance.
- Config reference is @Volatile and swapped atomically on configure().

## Notes
- Message parameters are lambdas; if filtered by minLevel (and debug=false), the lambda is not evaluated.
- File destination appends a platform-specific newline after each entry.

## License
WTFPL
