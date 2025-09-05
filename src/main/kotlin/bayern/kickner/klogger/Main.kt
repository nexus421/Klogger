package bayern.kickner.klogger

fun main() {


    Logger.configure {
        logToConsole()
    }

    "".debugLog { "Bananarama" }

}