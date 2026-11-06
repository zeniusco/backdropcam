package com.backdropcam

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decodes a music file in the background, converts it to 48 kHz mono and loops it, so the
 * recorder can mix it under the voice. [read] never blocks: it returns silence if the decoder
 * has fallen behind.
 */
class MusicSource(private val file: File, private val outRate: Int = VideoRecorder.SAMPLE_RATE) {

    private val ring = FloatArray(outRate * 4) // 4 seconds of buffer
    private var writePos = 0L
    private var readPos = 0L
    private val lock = Object()
    @Volatile private var running = true
    private val thread = Thread({ decodeLoop() }, "music-decode").apply { start() }

    /** Adds the next [n] music samples, times [gain], into [out] (clipped). */
    fun mixInto(out: ShortArray, n: Int, gain: FloatArray) {
        synchronized(lock) {
            for (i in 0 until n) {
                val m = if (readPos < writePos) ring[(readPos++ % ring.size).toInt()] else 0f
                val v = out[i] + m * gain[i] * 32767f
                out[i] = v.roundToInt().coerceIn(-32768, 32767).toShort()
            }
            lock.notifyAll()
        }
    }

    fun close() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread.join(500)
    }

    private fun push(sample: Float) {
        synchronized(lock) {
            while (running && writePos - readPos >= ring.size) lock.wait(50)
            ring[(writePos++ % ring.size).toInt()] = sample
        }
    }

    private fun decodeLoop() {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var pos = 0.0 // resampling position, in input samples
            var prev = 0f
            val info = MediaCodec.BufferInfo()
            while (running) {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        // End of the song: start again from the top (loop).
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    } else {
                        c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val out = c.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val frames = info.size / (channels * if (isFloat) 4 else 2)
                    val step = inRate.toDouble() / outRate
                    val fb = if (isFloat) out.asFloatBuffer() else null
                    val sb = if (isFloat) null else out.asShortBuffer()
                    for (f in 0 until frames) {
                        var mono = 0f
                        for (ch in 0 until channels) {
                            mono += if (fb != null) fb.get(f * channels + ch) else sb!!.get(f * channels + ch) / 32768f
                        }
                        mono /= channels
                        // Linear resampling to 48 kHz.
                        while (pos < 1.0) {
                            push(prev + (mono - prev) * pos.toFloat())
                            pos += step
                        }
                        pos -= 1.0
                        prev = mono
                        if (!running) break
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) c.flush()
                }
            }
        } catch (t: Throwable) {
            Log.e("MusicSource", "music decoding stopped", t)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            ex.release()
        }
    }

    companion object {
        /**
         * Music level that follows the voice: drops to [duckTo] within ~60 ms when you speak and
         * comes back over ~700 ms after you stop. Returns the per-sample gain in [out].
         */
        class Ducker(private val sampleRate: Int) {
            private var env = 1f
            private var holdSamples = 0
            fun gains(voice: ShortArray, n: Int, volume: Float, duck: Boolean, duckTo: Float, out: FloatArray) {
                var sum = 0.0
                for (i in 0 until n) sum += abs(voice[i].toInt()).toDouble()
                val speaking = duck && sum / n.coerceAtLeast(1) > 700 // roughly normal speech level
                if (speaking) holdSamples = sampleRate * 3 / 10 // keep ducked 0.3 s after a word
                val target = if (speaking || holdSamples > 0) duckTo else 1f
                val attackK = 1f / (sampleRate * 0.03f)  // ~30 ms to duck
                val releaseK = 1f / (sampleRate * 0.3f)  // ~300 ms time constant to come back
                for (i in 0 until n) {
                    env += (target - env) * if (target < env) attackK else releaseK
                    out[i] = env * volume
                    if (holdSamples > 0) holdSamples--
                }
            }
        }
    }
}
