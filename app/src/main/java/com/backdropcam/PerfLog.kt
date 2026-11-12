package com.backdropcam

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * A small running record of how the app is doing (speed of each step, memory, heat), one line
 * every few seconds, kept for this session and the one before. It is only ever sent when the
 * user chooses "Send report", and holds no pictures, sound or personal data.
 */
object PerfLog {
    private const val FILE = "perf_log.txt"
    private const val PREVIOUS = "perf_log_prev.txt"
    private val lines = ArrayDeque<String>()
    private val errors = LinkedHashMap<String, Int>()
    private val start = SystemClock.elapsedRealtime()
    private var begun = false

    /** Call once per app start: the last session's record is kept as "previous". */
    @Synchronized fun begin(context: Context) {
        if (begun) return
        begun = true
        runCatching {
            val cur = File(context.filesDir, FILE)
            if (cur.exists()) {
                val prev = File(context.filesDir, PREVIOUS)
                prev.delete()
                cur.renameTo(prev)
            }
        }
    }

    @Synchronized fun add(line: String) {
        lines.addLast("+${(SystemClock.elapsedRealtime() - start) / 1000}s $line")
        while (lines.size > 240) lines.removeFirst()
    }

    /** Notes a problem that was caught (so the app carried on), with how often it happened. */
    @Synchronized fun error(where: String, t: Throwable) {
        val key = "$where: ${t.javaClass.simpleName}: ${t.message} @ ${t.stackTrace.firstOrNull()}".take(300)
        errors[key] = (errors[key] ?: 0) + 1
        if (errors.size > 20) errors.remove(errors.keys.first())
    }

    @Synchronized fun dump(maxLines: Int): String {
        val sb = StringBuilder()
        if (errors.isNotEmpty()) {
            sb.append("Caught problems:\n")
            errors.forEach { (k, n) -> sb.append("  ").append(n).append("x ").append(k).append('\n') }
        }
        lines.toList().takeLast(maxLines).forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    fun save(context: Context) {
        runCatching { File(context.filesDir, FILE).writeText(dump(240)) }
    }

    fun previous(context: Context, maxLines: Int): String = runCatching {
        File(context.filesDir, PREVIOUS).takeIf { it.exists() }?.readLines()?.takeLast(maxLines)?.joinToString("\n") ?: ""
    }.getOrDefault("")
}
