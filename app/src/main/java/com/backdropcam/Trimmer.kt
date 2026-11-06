package com.backdropcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cuts the start and end off a recording without re-encoding (fast, no quality loss).
 * The start snaps back to the previous key frame; recordings have one every second.
 */
object Trimmer {

    fun durationUs(context: Context, uri: Uri): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(context, uri)
            val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release()
            ms * 1000
        }
    } catch (e: Exception) {
        0L
    }

    /** Writes [startUs]..[endUs] of [src] as a new gallery video and returns its URI. */
    fun trim(context: Context, src: Uri, startUs: Long, endUs: Long): Uri {
        val resolver = context.contentResolver
        val name = "BackdropCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_trim.mp4"
        var pending: Uri? = null
        var file: File? = null
        val pfd = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackdropCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            pending = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create gallery entry")
            resolver.openFileDescriptor(pending, "rw") ?: error("Cannot open gallery entry")
        } else null
        val muxer = if (pfd != null) {
            MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "BackdropCam")
            dir.mkdirs()
            file = File(dir, name)
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        val ex = MediaExtractor()
        var ok = false
        try {
            ex.setDataSource(context, src, null)
            val map = IntArray(ex.trackCount) { -1 }
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = muxer.addTrack(ex.getTrackFormat(i))
                    ex.selectTrack(i)
                }
            }
            muxer.start()
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = ex.sampleTime.coerceAtLeast(0L)
            val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val t = ex.sampleTime
                if (t > endUs) {
                    if (!ex.advance()) break
                    continue
                }
                if (t >= base && map[track] >= 0) {
                    buf.clear()
                    val size = ex.readSampleData(buf, 0)
                    if (size > 0) {
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, t - base, flags)
                        muxer.writeSampleData(map[track], buf, info)
                        samples++
                    }
                }
                if (!ex.advance()) break
            }
            muxer.stop()
            ok = samples > 0
        } finally {
            ex.release()
            runCatching { muxer.release() }
            pfd?.close()
        }

        if (pending != null) {
            if (!ok) {
                resolver.delete(pending, null, null)
                error("Nothing left after trimming")
            }
            resolver.update(pending, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return pending
        }
        val f = file!!
        if (!ok) {
            f.delete()
            error("Nothing left after trimming")
        }
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, u ->
            scanned = u
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(f)
    }
}
