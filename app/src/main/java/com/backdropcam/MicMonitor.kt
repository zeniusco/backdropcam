package com.backdropcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/** Listens to the microphone (without recording) so the user can test the level. */
class MicMonitor(private val gain: () -> Float, private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val rate = VideoRecorder.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 8192)
                )
            } catch (e: Exception) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                running = false
                return@Thread
            }
            val pcm = ShortArray(2048)
            rec.startRecording()
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n > 0) onLevel(AudioLevel.process(pcm, n, gain(), false))
            }
            runCatching { rec.stop() }
            rec.release()
        }, "mic-monitor").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
