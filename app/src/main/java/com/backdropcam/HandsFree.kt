package com.backdropcam

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.abs
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

    fun reset() {
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
        val repeatAfter = if (advice == PERFECT) Long.MAX_VALUE else 7000L
        if (advice != lastSpoken || now - lastSpokenAt > repeatAfter) {
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
 * Listens for "start" (or "record", "action") while the app is idle and calls [onStart].
 * Uses Android's speech recogniser, preferring the offline model.
 */
class VoiceCommands(private val context: Context, private val onStart: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var listeningSince = 0L

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active || !available) return
        active = true
        listeningSince = System.currentTimeMillis()
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.run {
            runCatching { cancel() }
            destroy()
        }
        recognizer = null
    }

    private fun listen() {
        if (!active) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }.onFailure { retry(1500) }
    }

    private fun retry(delayMs: Long) {
        if (!active) return
        main.postDelayed({ listen() }, delayMs)
    }

    private fun matches(results: Bundle?): Boolean {
        // Ignore anything heard right after listening started (echo of the phone's own voice).
        if (System.currentTimeMillis() - listeningSince < 900) return false
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        return list.any { phrase ->
            phrase.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).any { it in START_WORDS }
        }
    }

    private fun fire() {
        stop()
        onStart()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            if (active && matches(partialResults)) fire()
        }
        override fun onResults(results: Bundle?) {
            if (!active) return
            if (matches(results)) fire() else retry(150)
        }
        override fun onError(error: Int) {
            retry(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 300)
        }
    }

    companion object {
        private val START_WORDS = setOf("start", "action", "record", "begin", "go")
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
