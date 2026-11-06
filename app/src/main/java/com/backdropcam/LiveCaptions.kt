package com.backdropcam

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** A recognised word with its time in the recording (microseconds). */
class Word(val text: String, val startUs: Long, val endUs: Long)

/**
 * Turns the recording's own sound into text while recording (offline, English).
 * Recognition runs on its own thread so it never disturbs the audio encoder.
 * [onCaption] gets the text to show now; [onWords] gets newly recognised words (for the
 * voice-following teleprompter). Both are called on the main thread.
 */
class LiveTranscriber(
    private val recognizer: Recognizer,
    private val onCaption: (String) -> Unit,
    private val onWords: (List<String>) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val finals = ArrayList<Word>()
    private var streamStartUs = -1L
    private var lastPartial = ""
    private var lastFinalText = ""
    private val clearCaption = Runnable { onCaption("") }

    /** Feed 48 kHz mono audio from the recorder (copied; returns immediately). */
    fun feed48k(pcm: ShortArray, n: Int, startUs: Long) {
        val down = ShortArray(n / 3)
        var j = 0
        var i = 0
        while (i + 2 < n && j < down.size) {
            down[j++] = ((pcm[i] + pcm[i + 1] + pcm[i + 2]) / 3).toShort()
            i += 3
        }
        runCatching { worker.execute { process(down, j, startUs) } }
    }

    private fun process(pcm: ShortArray, n: Int, startUs: Long) {
        if (streamStartUs < 0) streamStartUs = startUs
        if (recognizer.acceptWaveForm(pcm, n)) {
            val words = parseWords(recognizer.result)
            if (words.isNotEmpty()) {
                synchronized(finals) { finals.addAll(words) }
                lastFinalText = words.joinToString(" ") { it.text }
                post(lastFinalText, words.map { it.text })
            }
            lastPartial = ""
        } else {
            val partial = runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrDefault("")
            if (partial.isNotBlank() && partial != lastPartial) {
                val newWords = partial.split(" ").drop(lastPartial.split(" ").filter { it.isNotBlank() }.size)
                lastPartial = partial
                post(partial, newWords)
            }
        }
    }

    private fun post(caption: String, newWords: List<String>) {
        val tail = caption.split(" ").takeLast(MAX_CAPTION_WORDS).joinToString(" ")
        main.post {
            onCaption(tail)
            if (newWords.isNotEmpty()) onWords(newWords)
            main.removeCallbacks(clearCaption)
            main.postDelayed(clearCaption, 2500) // clear the caption after a pause in speech
        }
    }

    private fun parseWords(json: String): List<Word> {
        val arr = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map {
            val w = arr.getJSONObject(it)
            Word(
                w.optString("word"),
                streamStartUs + (w.optDouble("start") * 1_000_000).toLong(),
                streamStartUs + (w.optDouble("end") * 1_000_000).toLong(),
            )
        }.filter { it.text.isNotBlank() && it.text != "[unk]" }
    }

    /** Stops listening and returns every word recognised, in order. */
    fun finish(): List<Word> {
        main.removeCallbacks(clearCaption)
        worker.shutdown()
        runCatching { worker.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching {
            val last = parseWords(recognizer.finalResult)
            synchronized(finals) { finals.addAll(last) }
        }
        recognizer.close()
        return synchronized(finals) { ArrayList(finals) }
    }

    companion object {
        private const val MAX_CAPTION_WORDS = 9
    }
}

/**
 * Follows the speaker through a teleprompter script: each recognised word is matched a little
 * ahead in the script, and the position only moves forward.
 */
class ScriptFollower(script: String) {
    private class Token(val word: String, val start: Int)

    private val tokens = Regex("[\\p{L}\\p{N}']+").findAll(script).map {
        Token(normalize(it.value), it.range.first)
    }.toList()
    private var pos = 0

    /** Returns the character offset now being spoken, or null if nothing matched. */
    fun advance(words: List<String>): Int? {
        var moved = false
        for (raw in words) {
            val w = normalize(raw)
            if (w.length < 2) continue
            // Look a few words ahead; short common words only very close by.
            val window = if (w.length <= 3) 4 else 15
            for (k in pos until minOf(tokens.size, pos + window)) {
                if (tokens[k].word == w) {
                    pos = k + 1
                    moved = true
                    break
                }
            }
        }
        return if (moved && tokens.isNotEmpty()) tokens[minOf(pos, tokens.size - 1)].start else null
    }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).trim('\'')
}

/** Writes recognised words as an .srt subtitle file into Documents/BackdropCam. */
object SubtitleWriter {

    fun save(context: Context, videoName: String, words: List<Word>): String? {
        if (words.isEmpty()) return null
        val srt = toSrt(words)
        val name = videoName.substringBeforeLast('.') + ".srt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/BackdropCam")
            }
            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(srt.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BackdropCam")
            dir.mkdirs()
            File(dir, name).writeText(srt)
        }
        return "Documents/BackdropCam/$name"
    }

    /** Groups words into short cues: at most 7 words, 3 seconds, or until a pause. */
    fun toSrt(words: List<Word>): String {
        val sb = StringBuilder()
        var i = 0
        var cue = 1
        while (i < words.size) {
            val start = words[i].startUs
            var j = i
            while (j + 1 < words.size && j - i < 6 &&
                words[j + 1].endUs - start < 3_000_000 && words[j + 1].startUs - words[j].endUs < 700_000
            ) j++
            sb.append(cue++).append('\n')
                .append(time(start)).append(" --> ").append(time(words[j].endUs)).append('\n')
                .append(words.subList(i, j + 1).joinToString(" ") { it.text }).append("\n\n")
            i = j + 1
        }
        return sb.toString()
    }

    private fun time(us: Long): String {
        val ms = (us / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
