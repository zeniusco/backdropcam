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
        dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun file(name: String) = File(dir, name)

    /** Copies (and downsizes) a picked image into app storage. */
    fun add(uri: Uri): File {
        val bmp = ImageUtils.decode({ context.contentResolver.openInputStream(uri)!! }, 3840)
            ?: error("Unsupported image")
        val f = File(dir, "bd_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        bmp.recycle()
        return f
    }

    fun load(name: String): Bitmap? {
        val f = file(name)
        if (!f.exists()) return null
        return ImageUtils.decode({ f.inputStream() }, 4096)
    }

    fun thumbnail(f: File): Bitmap? = ImageUtils.decode({ f.inputStream() }, 200)

    fun delete(name: String) {
        file(name).delete()
    }
}

object ImageUtils {
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
