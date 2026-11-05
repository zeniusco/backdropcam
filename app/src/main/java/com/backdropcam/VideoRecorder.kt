package com.backdropcam

import android.content.ContentResolver
import android.content.ContentValues
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encodes the composited GL output (H.264 via an input Surface) plus microphone audio (AAC)
 * into an MP4 stored in the gallery under Movies/BackdropCam.
 *
 * Timestamps for both tracks are measured against [startNs] (System.nanoTime at start).
 */
class VideoRecorder(
    private val resolver: ContentResolver,
    val width: Int,
    val height: Int,
    private val withAudio: Boolean,
    private val fps: Int = 30,
) {
    val startNs = System.nanoTime()
    lateinit var inputSurface: Surface; private set

    private val videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false

    private val uri: Uri
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val sink: MuxSink

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "BackdropCam_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackdropCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create gallery entry")
        pfd = resolver.openFileDescriptor(uri, "rw") ?: error("Cannot open gallery entry")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        sink = MuxSink(muxer, if (withAudio) 2 else 1)

        val bitrate = (width.toLong() * height * fps * 0.14).toLong().coerceIn(6_000_000L, 80_000_000L).toInt()
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel51)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject High/5.1: retry with defaults.
            vf.removeKey(MediaFormat.KEY_PROFILE); vf.removeKey(MediaFormat.KEY_LEVEL)
            videoCodec.reset()
            videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = videoCodec.createInputSurface()
    }

    fun start() {
        videoCodec.start()
        videoThread = Thread({ drain(videoCodec, true) }, "video-drain").also { it.start() }
        if (withAudio) startAudio()
    }

    /** Call after the GL engine detached the encoder surface. Returns the gallery URI. */
    fun finish(): Uri {
        videoCodec.signalEndOfInputStream()
        audioRunning = false
        videoThread?.join()
        audioThread?.join()
        videoCodec.stop(); videoCodec.release()
        audioCodec?.run { stop(); release() }
        audioRecord?.run { stop(); release() }
        sink.stop()
        pfd.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    // ------------------------------------------------------------------ audio

    private fun startAudio() {
        val rate = 44100
        val channelCfg = AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(rate, channelCfg, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, rate, channelCfg,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        rec.startRecording()
        audioCodec = codec; audioRecord = rec
        audioRunning = true

        audioThread = Thread({
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArray(4096)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        if (audioRunning) {
                            val n = rec.read(pcm, 0, minOf(pcm.size, buf.remaining()))
                            if (n > 0) {
                                buf.put(pcm, 0, n)
                                val ptsUs = (System.nanoTime() - startNs) / 1000 - n * 1_000_000L / (rate * 2)
                                codec.queueInputBuffer(idx, 0, n, maxOf(0L, ptsUs), 0)
                            } else {
                                codec.queueInputBuffer(idx, 0, 0, (System.nanoTime() - startNs) / 1000, 0)
                            }
                        } else {
                            codec.queueInputBuffer(
                                idx, 0, 0, (System.nanoTime() - startNs) / 1000,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }
                outputDone = pump(codec, info, false, 0)
            }
        }, "audio-drain").also { it.start() }
    }

    // ------------------------------------------------------------------ draining

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        var done = false
        while (!done) done = pump(codec, info, isVideo, 10_000)
    }

    private val trackIndex = IntArray(2) { -1 }

    /** Handles one output buffer. Returns true on end of stream. */
    private fun pump(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean, timeoutUs: Long): Boolean {
        val slot = if (isVideo) 0 else 1
        val idx = codec.dequeueOutputBuffer(info, timeoutUs)
        when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                trackIndex[slot] = sink.addTrack(codec.outputFormat)
            }
            idx >= 0 -> {
                val buf = codec.getOutputBuffer(idx)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && trackIndex[slot] >= 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    sink.write(trackIndex[slot], buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }
        return false
    }

    /** Starts the muxer once every track is known and queues early samples until then. */
    private class MuxSink(private val muxer: MediaMuxer, private val expectedTracks: Int) {
        private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

        private var added = 0
        private var started = false
        private val pending = ArrayList<Pending>()

        @Synchronized fun addTrack(format: MediaFormat): Int {
            val i = muxer.addTrack(format)
            added++
            if (added == expectedTracks) {
                muxer.start(); started = true
                pending.forEach { write(it.track, ByteBuffer.wrap(it.data), it.info) }
                pending.clear()
            }
            return i
        }

        @Synchronized fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started) {
                muxer.writeSampleData(track, buf, info)
            } else {
                val bytes = ByteArray(info.size).also { buf.get(it) }
                val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending.add(Pending(track, bytes, copy))
            }
        }

        @Synchronized fun stop() {
            if (started) { muxer.stop() }
            muxer.release()
        }
    }

    companion object {
        /** True when the device's AVC encoder can produce this size (checks both orientations). */
        fun isSizeSupported(w: Int, h: Int): Boolean = try {
            val caps = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).let { c ->
                val v = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                c.release(); v
            }
            caps.isSizeSupported(w, h) || caps.isSizeSupported(h, w)
        } catch (e: Exception) {
            w <= 1920 && h <= 1080
        }
    }
}
