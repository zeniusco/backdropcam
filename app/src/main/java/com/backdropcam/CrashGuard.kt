package com.backdropcam

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the error of a crash so the next start can offer to send it. */
object CrashGuard {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val app: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val mem = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                File(app.filesDir, FILE).writeText(
                    "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), app memory limit ${mem} MB\n" +
                        "Thread: ${t.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastReport(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.take(8000)

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

/**
 * Why Android ended the app last time. An ordinary error leaves a report through [CrashGuard],
 * but when Android itself closes the app (out of memory, the app froze, or a crash inside the
 * graphics or camera layer) nothing in the app gets to run. Android 11 and newer keep a record
 * of it, which is read here at the next start.
 */
object ExitInfo {
    class Last(
        /** False when the app was simply closed by the user or the system in a normal way. */
        val abnormal: Boolean,
        /** The phone ran out of memory, or the app froze: lighter settings help, not safe mode. */
        val memoryOrFreeze: Boolean,
        /** A crash inside the graphics, camera or cut-out layer (or one of unknown kind). */
        val nativeCrash: Boolean,
        /** One plain sentence for the user. */
        val summary: String,
        /** Technical details for the error report. */
        val details: String,
    )

    fun last(context: Context): Last? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching { read(context) }.getOrNull()
    }

    @androidx.annotation.RequiresApi(30)
    private fun read(context: Context): Last? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        // Only the camera screen's own process (not the small graphics-check helper).
        val info = am.getHistoricalProcessExitReasons(null, 0, 8)
            .firstOrNull { it.processName == context.packageName } ?: return null
        val desc = info.description ?: ""
        // Reason numbers are from android.app.ApplicationExitInfo.
        val (name, summary) = when (info.reason) {
            3 -> "LOW_MEMORY" to "Android closed the app because the phone ran out of memory."
            // A plain "kill" signal is how phones that do not report low memory show the same thing.
            2 -> "SIGNALED(${info.status})" to
                if (info.status == 9) "Android stopped the app, most likely because the phone ran out of memory."
                else "The app was stopped by the system (signal ${info.status})."
            4 -> "CRASH" to "The app hit an error."
            5 -> "CRASH_NATIVE" to "The app crashed inside the phone's graphics, camera or sound layer."
            6 -> "ANR" to "The app froze for several seconds and Android closed it."
            7 -> "INITIALIZATION_FAILURE" to "The app could not start."
            9 -> "EXCESSIVE_RESOURCE_USAGE" to "Android closed the app for using too much of the phone's resources."
            1 -> "EXIT_SELF" to ""
            8 -> "PERMISSION_CHANGE" to ""
            10 -> "USER_REQUESTED" to ""
            11 -> "USER_STOPPED" to ""
            12 -> "DEPENDENCY_DIED" to ""
            15 -> "PACKAGE_STATE_CHANGE" to ""
            16 -> "PACKAGE_UPDATED" to ""
            else -> "OTHER(${info.reason})" to
                if (desc.contains("remove task", true)) "" else "Android closed the app."
        }
        val abnormal = summary.isNotEmpty()
        val sb = StringBuilder()
        sb.append("Exit reason: ").append(name).append('\n')
        if (desc.isNotBlank()) sb.append("Description: ").append(desc).append('\n')
        sb.append("When: ").append(java.util.Date(info.timestamp)).append('\n')
        sb.append("App memory at that moment: ").append(info.pss / 1024).append(" MB (pss), ")
            .append(info.rss / 1024).append(" MB (rss)\n")
        sb.append("Importance: ").append(info.importance).append('\n')
        if (abnormal) runCatching {
            info.traceInputStream?.use { input ->
                val bytes = input.readBytes()
                val text = if (info.reason == 6) anrMainThread(String(bytes)) else readableParts(bytes)
                if (text.isNotBlank()) sb.append("\nTrace:\n").append(text).append('\n')
            }
        }
        return Last(
            abnormal = abnormal,
            memoryOrFreeze = info.reason == 3 || info.reason == 6 || info.reason == 9 || (info.reason == 2 && info.status == 9),
            nativeCrash = info.reason == 5,
            summary = summary,
            details = sb.toString(),
        )
    }

    /** The part of a freeze report that says what the app's main thread was stuck on. */
    private fun anrMainThread(trace: String): String {
        val start = trace.indexOf("\"main\" ")
        if (start < 0) return trace.take(3000)
        val end = trace.indexOf("\n\n", start).let { if (it < 0) trace.length else it }
        return trace.substring(start, end).lines().take(70).joinToString("\n")
    }

    /** A native crash record is binary; keep the readable words (signal, library and function names). */
    private fun readableParts(bytes: ByteArray): String {
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) cur.append(c.toChar()) else {
                if (cur.length >= 6) out.add(cur.toString())
                cur.setLength(0)
            }
            if (out.size >= 160) break
        }
        return out.joinToString("\n").take(6000)
    }
}
