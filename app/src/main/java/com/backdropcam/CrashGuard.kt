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
