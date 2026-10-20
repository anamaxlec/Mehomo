package dev.memoh.android

import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/** One local report. Exception messages and thread names can contain request/user data. */
internal class CrashReportStore(private val file: File, val environment: String) {
    fun read(): String? = file.takeIf { it.isFile }?.readText()?.takeIf(String::isNotBlank)

    fun handler(previous: Thread.UncaughtExceptionHandler?, mainThread: Thread): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                record(error, main = thread === mainThread)
            } catch (_: Throwable) {
                // Reporting must never intercept Android's original crash handling.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }

    private fun record(error: Throwable, main: Boolean) {
        val report = buildString {
            appendLine("Mehomo 本地崩溃记录")
            appendLine(environment)
            appendLine("Time: ${Instant.now()}")
            appendLine("Thread: ${if (main) "main" else "background"}")
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var framesLeft = 160
            var causesLeft = 8
            while (cause != null && seen.add(cause) && causesLeft-- > 0 && framesLeft > 0) {
                appendLine(cause.javaClass.name)
                for (frame in cause.stackTrace.take(framesLeft)) {
                    appendLine("    at $frame")
                    framesLeft--
                }
                cause = cause.cause
                if (cause != null) append("Caused by: ")
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(report)
    }
}
