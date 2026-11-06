package com.backdropcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Encodes the composited GL output (H.264 through an input Surface) plus microphone audio (AAC)
 * into an MP4 in the gallery (Movies/BackdropCam). Works on Android 8.0 and newer.
 */
class VideoRecorder(
    context: Context,
    val width: Int,
    val height: Int,
    withAudio: Boolean,
    private val gain: () -> Float,
    private val muted: () -> Boolean,
    private val onLevel: (Float) -> Unit,
    private val onPcm: ((ShortArray, Int, Long) -> Unit)? = null,
    /** Background music mixed under the voice (closed when the recording finishes). */
    private val music: MusicSource? = null,
    private val musicVolume: () -> Float = { 0f },
    private val musicDuck: () -> Boolean = { true },
    private val fps: Int = 30,
    /** Use H.265/HEVC (about half the file size) when the phone can encode this size with it. */
    preferHevc: Boolean = true,
) {
    /** "video/hevc" or "video/avc", whichever is used for this recording. */
    val videoMime: String = if (preferHevc && hevcSupports(width, height)) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    @Volatile var paused = false
        private set
    @Volatile private var pausedAtNs = 0L
    @Volatile private var pauseOffsetNs = 0L

    fun pause() {
        if (!paused) {
            pausedAtNs = System.nanoTime()
            paused = true
        }
    }

    fun resume() {
        if (paused) {
            pauseOffsetNs += System.nanoTime() - pausedAtNs
            paused = false
            audioResync = true
        }
    }

    // Audio timestamps come from the sample count (smooth), re-based on the clock at start and
    // after every pause.
    @Volatile private var audioResync = true
    private var audioBaseUs = 0L
    private var audioSamples = 0L

    /** Recording clock in ns: stops while paused, so the video has no gap. */
    fun ptsNs(): Long = (if (paused) pausedAtNs else System.nanoTime()) - startNs - pauseOffsetNs

    /** Samples after this time (us) are dropped, e.g. to cut the spoken "stop" from the end. */
    @Volatile var cutoffUs = Long.MAX_VALUE

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    val startNs = System.nanoTime()
    val displayName = "BackdropCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

    lateinit var inputSurface: Surface
        private set
    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false

    // Output: MediaStore entry on Android 10+, a public file on older versions.
    private var pendingUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var file: File? = null
    private val muxer: MediaMuxer
    private lateinit var sink: MuxSink

    init {
        muxer = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackdropCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create gallery entry")
            pendingUri = uri
            val fd = resolver.openFileDescriptor(uri, "rw") ?: error("Cannot open gallery entry")
            pfd = fd
            MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "BackdropCam")
            dir.mkdirs()
            val f = File(dir, displayName)
            file = f
            MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        try {
            val audioOk = withAudio && setupAudio()
            sink = MuxSink(muxer, if (audioOk) 2 else 1)
            videoCodec = createVideoEncoder()
            inputSurface = videoCodec.createInputSurface()
        } catch (t: Throwable) {
            // Leave nothing behind: an empty, invisible "pending" video in the gallery.
            runCatching { muxer.release() }
            runCatching { audioCodec?.release() }
            runCatching { audioRecord?.release() }
            runCatching { pfd?.close() }
            pendingUri?.let { runCatching { resolver.delete(it, null, null) } }
            file?.delete()
            throw t
        }
    }

    private fun createVideoEncoder(): MediaCodec {

        // Bits per pixel per frame: HEVC needs about 40 % less than H.264 for the same quality.
        // 4K at 30 fps comes to ~12 Mbit/s (HEVC) or ~19 Mbit/s (H.264); 1080p to ~3 / ~5.
        val hevc = videoMime == MediaFormat.MIMETYPE_VIDEO_HEVC
        val bpp = if (hevc) 0.055 else 0.09
        val bitrate = (width.toLong() * height * fps * bpp).toLong().coerceIn(2_000_000L, 40_000_000L).toInt()
        val vf = MediaFormat.createVideoFormat(videoMime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (!hevc) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel51)
            }
        }
        val hwName = if (videoMime == MediaFormat.MIMETYPE_VIDEO_HEVC) hevcEncoder?.name else null
        val codec = if (hwName != null) MediaCodec.createByCodecName(hwName) else MediaCodec.createEncoderByType(videoMime)
        try {
            codec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some (older) encoders reject High profile: retry with their defaults.
            vf.removeKey(MediaFormat.KEY_PROFILE)
            vf.removeKey(MediaFormat.KEY_LEVEL)
            codec.reset()
            codec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return codec
    }

    fun start() {
        videoCodec.start()
        videoThread = Thread({ drainVideo() }, "video-drain").also { it.start() }
        if (audioCodec != null) startAudioThread()
    }

    /** Call after the GL engine detached the encoder surface. Returns the gallery URI. */
    private val ducker = MusicSource.Companion.Ducker(SAMPLE_RATE)
    private val musicGain = FloatArray(2048)

    fun finish(): Uri {
        videoCodec.signalEndOfInputStream()
        audioRunning = false
        videoThread?.join(5000)
        audioThread?.join(5000)
        music?.close()
        runCatching { videoCodec.stop() }
        videoCodec.release()
        audioCodec?.run { runCatching { stop() }; release() }
        audioRecord?.run { runCatching { stop() }; release() }
        runCatching { inputSurface.release() }
        val ok = sink.stop()
        val why = failure ?: "Recording was too short"

        if (Build.VERSION.SDK_INT >= 29) {
            pfd?.close()
            val uri = pendingUri!!
            if (!ok) {
                resolver.delete(uri, null, null)
                error(why)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        val f = file!!
        if (!ok) {
            f.delete()
            error(why)
        }
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(appContext, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(f)
    }

    // ------------------------------------------------------------------ audio

    @SuppressLint("MissingPermission")
    private fun setupAudio(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 4, 16384)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 4, 16384)
                )
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioRecord = rec
            audioCodec = codec
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startAudioThread() {
        val codec = audioCodec!!
        val rec = audioRecord!!
        codec.start()
        rec.startRecording()
        audioRunning = true
        audioThread = Thread({
            val info = MediaCodec.BufferInfo()
            val pcm = ShortArray(2048)
            var lastLevelMs = 0L
            var inputDone = false
            var outputDone = false
            var readErrors = 0
            while (!outputDone) {
                if (!inputDone && paused && audioRunning) {
                    rec.read(pcm, 0, pcm.size) // keep draining the mic, but drop the audio
                } else if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        val nowUs = ptsNs() / 1000
                        if (audioRunning) {
                            val n = rec.read(pcm, 0, minOf(pcm.size, buf.remaining() / 2))
                            if (n > 0) {
                                readErrors = 0
                                if (audioResync) {
                                    audioBaseUs = nowUs - n * 1_000_000L / SAMPLE_RATE
                                    audioSamples = 0
                                    audioResync = false
                                }
                                val ptsUs = audioBaseUs + audioSamples * 1_000_000L / SAMPLE_RATE
                                audioSamples += n
                                onPcm?.invoke(pcm, n, ptsUs) // raw audio, before gain and mute
                                val level = AudioLevel.process(pcm, n, gain(), muted())
                                if (music != null) {
                                    ducker.gains(pcm, n, musicVolume(), musicDuck(), 0.25f, musicGain)
                                    music.mixInto(pcm, n, musicGain)
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastLevelMs > 60) {
                                    lastLevelMs = now
                                    onLevel(level)
                                }
                                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
                                codec.queueInputBuffer(idx, 0, n * 2, max(0L, ptsUs), 0)
                            } else {
                                // The microphone gave nothing (another app may hold it). Keep the
                                // video going; after a while give up on sound for this recording.
                                codec.queueInputBuffer(idx, 0, 0, nowUs, 0)
                                if (++readErrors > 200) {
                                    audioRunning = false
                                } else {
                                    Thread.sleep(5)
                                }
                            }
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, nowUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                outputDone = pump(codec, info, AUDIO, 0)
            }
        }, "audio-drain").also { it.start() }
    }

    // ------------------------------------------------------------------ draining

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        var done = false
        while (!done) done = pump(videoCodec, info, VIDEO, 10_000)
    }

    private val trackIndex = intArrayOf(-1, -1)
    private val lastPts = longArrayOf(-1L, -1L)

    /** Why the recording failed (e.g. the storage filled up), or null. */
    @Volatile private var failure: String? = null

    /** Handles one output buffer. Returns true at end of stream (or once this track has failed). */
    private fun pump(codec: MediaCodec, info: MediaCodec.BufferInfo, slot: Int, timeoutUs: Long): Boolean {
        try {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> trackIndex[slot] = sink.addTrack(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && trackIndex[slot] >= 0 && info.presentationTimeUs > lastPts[slot] &&
                        info.presentationTimeUs <= cutoffUs && failure == null
                    ) {
                        lastPts[slot] = info.presentationTimeUs
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        sink.write(trackIndex[slot], buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
            return false
        } catch (t: Throwable) {
            // Typically the storage is full or the encoder died. Stop writing; finish() reports it.
            if (failure == null) failure = if (t is java.io.IOException || t.message?.contains("space", true) == true)
                "The phone's storage is full" else "Recording failed: ${t.message ?: t.javaClass.simpleName}"
            return true
        }
    }

    /** Starts the muxer once every track is known and queues early samples until then. */
    private class MuxSink(private val muxer: MediaMuxer, private val expectedTracks: Int) {
        private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

        private var added = 0
        private var started = false
        private var samples = 0
        private val pending = ArrayList<Pending>()
        private var firstTrackMs = 0L

        /** @return the muxer track, or -1 when the muxer already started without this track. */
        @Synchronized fun addTrack(format: MediaFormat): Int {
            if (started) return -1
            val i = muxer.addTrack(format)
            if (added++ == 0) firstTrackMs = System.currentTimeMillis()
            if (added == expectedTracks) start()
            return i
        }

        private fun start() {
            muxer.start()
            started = true
            pending.forEach { muxer.writeSampleData(it.track, ByteBuffer.wrap(it.data), it.info) }
            samples += pending.size
            pending.clear()
        }

        @Synchronized fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (!started && added > 0 && System.currentTimeMillis() - firstTrackMs > 2500) {
                // The other track (normally the sound) never showed up: save the video without it
                // rather than losing the whole recording.
                start()
            }
            if (started) {
                if (track < 0) return
                muxer.writeSampleData(track, buf, info)
                samples++
            } else {
                val bytes = ByteArray(info.size).also { buf.get(it) }
                val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending.add(Pending(track, bytes, copy))
            }
        }

        /** @return true if a playable file was written */
        @Synchronized fun stop(): Boolean {
            val ok = started && samples > 0
            try {
                if (started) muxer.stop()
            } catch (e: Exception) {
                return false.also { runCatching { muxer.release() } }
            }
            muxer.release()
            return ok
        }
    }

    companion object {
        private const val VIDEO = 0
        private const val AUDIO = 1
        const val SAMPLE_RATE = 48000

        /** The H.264 encoder's limits, looked up once (enumerating codecs is slow). */
        private val avcCapsCache: MediaCodecInfo.VideoCapabilities? by lazy {
            try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
            } catch (e: Exception) {
                null
            }
        }

        private fun avcCaps() = avcCapsCache

        /**
         * The phone's hardware H.265 encoder, if it has one. Software H.265 encoders are far too
         * slow for live video on most phones (they make everything lag), so they are never used.
         */
        private val hevcEncoder: MediaCodecInfo? by lazy {
            try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    info.isEncoder && isHardware(info) &&
                        info.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
            val n = info.name.lowercase(Locale.ROOT)
            return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
        }

        fun hevcSupports(w: Int, h: Int) = runCatching {
            hevcEncoder?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)?.videoCapabilities?.isSizeSupported(w, h) == true
        }.getOrDefault(false)

        /** True if the encoder can produce this size in either orientation. */
        fun isSizeSupported(w: Int, h: Int): Boolean {
            val caps = avcCaps() ?: return max(w, h) <= 1920
            return caps.isSizeSupported(w, h) || caps.isSizeSupported(h, w)
        }

        /** Largest size with the same aspect ratio that this phone's encoder accepts. */
        fun pickEncoderSize(w: Int, h: Int): Size {
            val evenW = w and 1.inv()
            val evenH = h and 1.inv()
            val caps = avcCaps() ?: return Size(evenW, evenH)
            if (caps.isSizeSupported(evenW, evenH)) return Size(evenW, evenH)
            for (longSide in intArrayOf(3840, 2560, 1920, 1280, 960, 640)) {
                if (longSide >= max(w, h)) continue
                val s = longSide.toFloat() / max(w, h)
                val cw = ((w * s) / 16f).roundToInt() * 16
                val ch = ((h * s) / 16f).roundToInt() * 16
                if (caps.isSizeSupported(cw, ch)) return Size(cw, ch)
            }
            return Size(evenW, evenH)
        }
    }
}

/** Applies software gain to 16-bit PCM in place and returns a 0..1 loudness level. */
object AudioLevel {
    fun process(pcm: ShortArray, n: Int, gain: Float, muted: Boolean): Float {
        if (muted) {
            java.util.Arrays.fill(pcm, 0, n, 0.toShort())
            return 0f
        }
        var sum = 0.0
        for (i in 0 until n) {
            val v = (pcm[i] * gain).roundToInt().coerceIn(-32768, 32767)
            pcm[i] = v.toShort()
            sum += v.toDouble() * v
        }
        val rms = sqrt(sum / max(n, 1)) / 32768.0
        val db = 20 * log10(rms.coerceAtLeast(1e-5))
        return ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
    }
}
