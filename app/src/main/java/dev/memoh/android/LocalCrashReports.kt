package dev.memoh.android

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Looper
import java.io.File
import java.time.Instant

internal object LocalCrashReports {
    private fun store(context: Context): CrashReportStore {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return CrashReportStore(File(context.filesDir, "diagnostics/last-crash.txt"),
            "App: ${info.versionName} ($versionCode)\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun install(context: Context) {
        val reports = store(context)
        Thread.setDefaultUncaughtExceptionHandler(reports.handler(
            Thread.getDefaultUncaughtExceptionHandler(), Looper.getMainLooper().thread))
    }

    fun read(context: Context): String? {
        val reports = store(context)
        val javaReport = reports.read()
        if (Build.VERSION.SDK_INT < 30) return javaReport
        val exit = runCatching { context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .firstOrNull { it.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) } }.getOrNull() ?: return javaReport
        val file = File(context.filesDir, "diagnostics/last-crash.txt")
        if (javaReport != null && file.lastModified() > exit.timestamp + 60_000) return javaReport
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
            else -> "Excessive resource usage"
        }
        return buildString {
            appendLine("Mehomo 系统退出记录")
            appendLine("Currently installed ${reports.environment}")
            appendLine("Time: ${Instant.ofEpochMilli(exit.timestamp)}")
            appendLine("Reason: $reason; status: ${exit.status}")
            // A later native crash must not be reported with an old Java exception's stack.
            if (javaReport != null && kotlin.math.abs(file.lastModified() - exit.timestamp) < 60_000) {
                appendLine(); append(javaReport)
            }
        }
    }
}
