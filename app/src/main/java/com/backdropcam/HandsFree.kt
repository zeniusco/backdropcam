package com.backdropcam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Speaks short prompts out loud through the phone speaker. */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val pending = HashMap<String, () -> Unit>()
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val r = tts.setLanguage(Locale.getDefault())
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US)
            tts.setSpeechRate(1.05f)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) {
                val cb = synchronized(pending) { pending.remove(id) } ?: return
                main.post(cb)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = onDone(id)
        })
    }

    val isSpeaking: Boolean get() = ready && tts.isSpeaking

    /** Speaks [text], interrupting anything already being said. [done] runs when finished. */
    fun say(text: String, done: (() -> Unit)? = null) {
        if (!ready) {
            done?.let { main.postDelayed(it, 600) }
            return
        }
        val id = "u${counter++}"
        if (done != null) synchronized(pending) { pending[id] = done }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        if (ready) tts.stop()
        synchronized(pending) { pending.clear() }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

/**
 * Turns "where is the person in the frame" into spoken coaching, e.g. "step back a little".
 * Only speaks when the advice changes and has been stable for a moment, so it never nags.
 */
class FramingCoach(private val speak: (String) -> Unit) {
    private var current: String? = null
    private var since = 0L
    private var lastSpoken: String? = null
    private var lastSpokenAt = 0L
    private var repeats = 0

    fun reset() {
        repeats = 0
        current = null
        lastSpoken = null
    }

    fun update(f: CompositeEngine.Framing) {
        val advice = adviceFor(f)
        val now = System.currentTimeMillis()
        if (advice != current) {
            current = advice
            since = now
            return
        }
        if (now - since < 900) return
        val repeatAfter = if (advice == PERFECT) Long.MAX_VALUE else 15000L
        if (advice != lastSpoken) repeats = 0
        if ((advice != lastSpoken || now - lastSpokenAt > repeatAfter) && repeats < 2) {
            if (advice == lastSpoken) repeats++
            lastSpoken = advice
            lastSpokenAt = now
            speak(advice)
        }
    }

    private fun adviceFor(f: CompositeEngine.Framing): String {
        if (f.coverage < 0.015f) return "I can't see you. Step into the frame."
        val height = f.bottom - f.top
        if (f.coverage > 0.6f) return "Too close. Step back a little."
        if (f.top < 0.015f && f.bottom > 0.97f && height > 0.95f) return "Your head is cut off. Step back a little."
        if (height < 0.4f) return "Come a little closer."
        if (f.centerX < 0.36f || f.centerX > 0.64f) {
            // Directions are from the speaker's point of view while facing the phone.
            val onImageLeft = f.centerX < 0.5f
            val moveRight = if (f.mirrored) onImageLeft else !onImageLeft
            return if (moveRight) "Move a little to your right." else "Move a little to your left."
        }
        if (f.top > 0.3f) return "Raise the phone a little, or come closer."
        return PERFECT
    }

    companion object {
        const val PERFECT = "Perfect, you're in the frame. Say start when you're ready."
    }
}

/**
 * Always-on, offline keyword listening with Vosk (no system beeps, no restart loops).
 *
 * While idle it listens to the microphone itself. While recording, the recorder hands it the
 * recording's own audio ([feed48k]), so "stop" works without fighting over the microphone.
 * Only a handful of words are recognised, which keeps it fast and avoids false triggers.
 */
class KeywordSpotter(context: Context, private val onWord: (word: String, atUs: Long) -> Unit) {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var streamStartUs = -1L
    private var listenThread: Thread? = null
    @Volatile private var listening = false
    private val down = ShortArray(8192)

    /** Words heard before this time (ms) are ignored, e.g. while the phone itself is talking. */
    @Volatile var mutedUntil = 0L

    val available: Boolean get() = model != null

    /** Unpacks and loads the speech model in the background (first launch takes a few seconds). */
    fun load(onReady: (Boolean) -> Unit) {
        Thread({
            val ok = try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val dir = unpackModel()
                model = Model(dir.absolutePath)
                reset()
                true
            } catch (t: Throwable) {
                Log.e("KeywordSpotter", "speech model failed to load", t)
                false
            }
            onReady(ok)
        }, "vosk-load").start()
    }

    private fun unpackModel(): File {
        val dir = File(appContext.filesDir, MODEL_DIR)
        val marker = File(dir, ".ready-$MODEL_VERSION")
        if (marker.exists()) return dir
        dir.deleteRecursively()
        val files = appContext.assets.open("$MODEL_DIR.files").bufferedReader().readLines().filter { it.isNotBlank() }
        for (path in files) {
            val out = File(appContext.filesDir, path)
            out.parentFile?.mkdirs()
            appContext.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        marker.createNewFile()
        return dir
    }

    /** Starts a fresh audio stream (word times are measured from the next fed sample). */
    fun reset() {
        synchronized(lock) {
            recognizer?.close()
            recognizer = model?.let { Recognizer(it, 16000f, GRAMMAR).apply { setWords(true) } }
            streamStartUs = -1L
        }
    }

    /** Feeds 16 kHz mono audio. [startUs] is the time of the first sample (any clock). */
    fun feed16k(pcm: ShortArray, n: Int, startUs: Long) {
        synchronized(lock) {
            val r = recognizer ?: return
            if (streamStartUs < 0) streamStartUs = startUs
            if (r.acceptWaveForm(pcm, n)) handle(r.result)
        }
    }

    /** Feeds 48 kHz mono audio from the recorder (down-sampled by 3). */
    fun feed48k(pcm: ShortArray, n: Int, startUs: Long) {
        var j = 0
        var i = 0
        while (i + 2 < n && j < down.size) {
            down[j++] = ((pcm[i] + pcm[i + 1] + pcm[i + 2]) / 3).toShort()
            i += 3
        }
        feed16k(down, j, startUs)
    }

    private fun handle(json: String) {
        if (System.currentTimeMillis() < mutedUntil) return
        val words = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return
        for (k in 0 until words.length()) {
            val w = words.getJSONObject(k)
            val word = w.optString("word")
            if (word !in KEYWORDS || w.optDouble("conf", 1.0) < 0.6) continue
            val atUs = streamStartUs + (w.optDouble("start", 0.0) * 1_000_000).toLong()
            onWord(word, atUs)
            return
        }
    }

    /** Listens to the microphone (idle mode). Call [stop] before recording starts. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || model == null) return
        listening = true
        reset()
        listenThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 8192)
                )
            } catch (e: Exception) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                listening = false
                return@Thread
            }
            val buf = ShortArray(1600)
            rec.startRecording()
            while (listening) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) feed16k(buf, n, System.nanoTime() / 1000)
            }
            runCatching { rec.stop() }
            rec.release()
        }, "keyword-listen").also { it.start() }
    }

    fun stop() {
        listening = false
        listenThread?.join(800)
        listenThread = null
    }

    fun close() {
        stop()
        synchronized(lock) {
            recognizer?.close()
            recognizer = null
        }
        model?.close()
        model = null
    }

    companion object {
        private const val MODEL_DIR = "vosk-en"
        private const val MODEL_VERSION = 1
        val START_WORDS = setOf("start", "action")
        val STOP_WORDS = setOf("stop", "cut")
        private val KEYWORDS = START_WORDS + STOP_WORDS
        private const val GRAMMAR = "[\"start\", \"action\", \"stop\", \"cut\", \"[unk]\"]"
    }
}

/**
 * Spots two sharp claps in a row in the recording's own audio, used to stop recording hands-free.
 * Feed 16-bit mono PCM in order; [onDoubleClap] gets the time of the first clap.
 */
class ClapDetector(private val sampleRate: Int, private val onDoubleClap: (firstClapUs: Long) -> Unit) {
    private val frame = sampleRate / 100 // 10 ms
    private var background = 300.0
    private var lastOnsetUs = -1_000_000L
    private var firstClapUs = -1L
    private var cooldownUntilUs = 0L

    fun feed(pcm: ShortArray, n: Int, startUs: Long) {
        var i = 0
        while (i + frame <= n) {
            var peak = 0
            var sum = 0.0
            for (k in i until i + frame) {
                val v = abs(pcm[k].toInt())
                if (v > peak) peak = v
                sum += v.toDouble() * v
            }
            val rms = sqrt(sum / frame)
            val t = startUs + i * 1_000_000L / sampleRate
            val onset = peak > 6500 && rms > background * 7 && t - lastOnsetUs > 90_000
            if (onset && t >= cooldownUntilUs) {
                lastOnsetUs = t
                if (firstClapUs >= 0 && t - firstClapUs in 120_000..750_000) {
                    val first = firstClapUs
                    firstClapUs = -1
                    cooldownUntilUs = t + 2_000_000
                    onDoubleClap(first)
                } else {
                    firstClapUs = t
                }
            } else if (!onset) {
                background = background * 0.97 + rms * 0.03
                if (background < 100) background = 100.0
            }
            i += frame
        }
    }
}
