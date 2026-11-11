package com.backdropcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlin.math.max

/** Keeps the user's backdrops inside the app so they are always one tap away. */
class BackdropStore(private val context: Context) {

    private val dir = File(context.filesDir, "backdrops").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".jpg") || f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun isVideo(name: String) = name.endsWith(".mp4")

    fun file(name: String) = File(dir, name)

    /** Copies a picked image (downsized) or video (as is, up to 300 MB) into app storage. */
    fun add(uri: Uri): File {
        val type = context.contentResolver.getType(uri) ?: ""
        if (type.startsWith("video/")) {
            val max = 300L * 1024 * 1024
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > max) error("Video is too large (over 300 MB)")
            val f = File(dir, "bd_${System.currentTimeMillis()}.mp4")
            // The size is not always known in advance: count while copying and stop at the limit.
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > max) error("Video is too large (over 300 MB)")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                f.delete()
                throw e
            }
            return f
        }
        val bmp = ImageUtils.decode({ context.contentResolver.openInputStream(uri)!! }, 3840)
            ?: error("Unsupported image")
        val f = File(dir, "bd_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        bmp.recycle()
        return f
    }

    /** [maxSide]: longest side to load at (smaller on phones with little memory). */
    fun load(name: String, maxSide: Int = 4096): Bitmap? {
        val f = file(name)
        if (!f.exists()) return null
        return ImageUtils.decode({ f.inputStream() }, maxSide)
    }

    fun thumbnail(f: File): Bitmap? =
        if (isVideo(f.name)) videoFrame(f, 200) else ImageUtils.decode({ f.inputStream() }, 200)

    /** First frame of a video backdrop, scaled to at most [maxSide]. */
    fun videoFrame(f: File, maxSide: Int): Bitmap? = runCatching {
        android.media.MediaMetadataRetriever().run {
            setDataSource(f.absolutePath)
            val frame = getFrameAtTime(0)
            release()
            frame?.let {
                val s = maxSide.toFloat() / max(it.width, it.height)
                if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt(), (it.height * s).toInt(), true) else it
            }
        }
    }.getOrNull()

    fun delete(name: String) {
        file(name).delete()
    }
}

object ImageUtils {
    /** Average colour (r, g, b in 0..1), used to match the person's light to a backdrop. */
    fun meanColor(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (small !== bmp) small.recycle()
        var r = 0f; var g = 0f; var b = 0f
        for (c in px) {
            r += ((c shr 16) and 0xFF) / 255f
            g += ((c shr 8) and 0xFF) / 255f
            b += (c and 0xFF) / 255f
        }
        return floatArrayOf(r / px.size, g / px.size, b / px.size)
    }

    /** Decodes an image no larger than [maxSide], honouring EXIF rotation. Works on Android 8+. */
    fun decode(open: () -> InputStream, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            m.postScale(s, s)
        }
        if (!m.isIdentity) {
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            bmp = out
        }
        return bmp
    }
}
