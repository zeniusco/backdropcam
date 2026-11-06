package com.backdropcam

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlin.math.abs
import kotlin.math.max

/**
 * MediaPipe person segmentation.
 *
 * Best quality uses the "selfie multiclass" model (background, hair, body skin, face skin,
 * clothes, other): person = 1 - background. It finds hair and clothing edges far better than the
 * small selfie model, which stays available as the fast option for older phones.
 *
 * Not thread safe: call from one thread only.
 */
class PersonSegmenter(context: Context, highQuality: Boolean, allowGpu: Boolean = true) {

    class Mask(val data: ByteArray, val width: Int, val height: Int)

    private val appContext = context.applicationContext
    private val model = if (highQuality) MODEL_HQ else MODEL_FAST
    private var usingGpu = allowGpu
    private var segmenter: ImageSegmenter = create()
    private var lastTs = 0L

    /**
     * Smoothing memory, kept separately for each picture region ("stream"): the whole picture and
     * the close-up crop around the person must never be blended with each other.
     */
    private class History(var mask: FloatArray? = null, var luma: FloatArray? = null)
    private val history = arrayOf(History(), History())


    @Volatile private var resetRequested = false

    private fun create(): ImageSegmenter {
        fun build(delegate: Delegate) = ImageSegmenter.createFromOptions(
            appContext,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(model).setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
        )
        if (usingGpu) {
            try {
                return build(Delegate.GPU)
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate unavailable, using CPU", t)
                usingGpu = false
            }
        }
        return build(Delegate.CPU)
    }

    /** Forget the previous mask, e.g. after the camera or orientation changed. Thread safe. */
    fun reset() {
        resetRequested = true
    }

    /**
     * Segments [bitmap]. Pass [resetSmoothing] when the picture region changed (e.g. the crop
     * around the person moved), so the previous mask is not blended in at the wrong place.
     */
    fun segment(bitmap: Bitmap, resetSmoothing: Boolean = false, stream: Int = 0): Mask? {
        if (resetRequested) {
            history.forEach { it.mask = null; it.luma = null }
            resetRequested = false
        }
        val h = history[stream.coerceIn(0, history.size - 1)]
        if (resetSmoothing) {
            h.mask = null
            h.luma = null
        }
        val ts = max(lastTs + 1, SystemClock.uptimeMillis())
        lastTs = ts
        val result = try {
            segmenter.segmentForVideo(BitmapImageBuilder(bitmap).build(), ts)
        } catch (t: Throwable) {
            if (usingGpu) {
                Log.w(TAG, "GPU inference failed, switching to CPU", t)
                runCatching { segmenter.close() }
                usingGpu = false
                segmenter = create()
                return null
            }
            throw t
        }
        val masks = result.confidenceMasks()
        if (!masks.isPresent || masks.get().isEmpty()) return null
        val list = masks.get()
        // With several masks, index 0 is the background: person = 1 - background.
        val invert = list.size >= 2
        val m = list[0]
        val n = m.width * m.height
        val cur = FloatArray(n)
        ByteBufferExtractor.extract(m).asFloatBuffer().get(cur, 0, n)

        // Brightness of the picture at each mask point, to tell real movement from model noise.
        val luma = lumaAt(bitmap, m.width, m.height)
        val p = h.mask?.takeIf { it.size == n }
        val pl = h.luma?.takeIf { it.size == n }
        val out = ByteArray(n)
        for (i in 0 until n) {
            var v = if (invert) 1f - cur[i] else cur[i]
            if (p != null && pl != null) {
                // Where the picture itself did not change, a changing mask is only model noise
                // (the flicker around your outline): hold it steady. Where the picture changed,
                // you moved: follow the new mask at once, so nothing trails behind you.
                val moved = abs(luma[i] - pl[i])
                val a = SMOOTHING * (1f - ((moved - 0.03f) / 0.09f).coerceIn(0f, 1f))
                v = a * p[i] + (1f - a) * v
            }
            cur[i] = v
            out[i] = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        h.mask = cur
        h.luma = luma
        return Mask(out, m.width, m.height)
    }

    private var pixels = IntArray(0)

    /** Brightness (0..1) of [bmp] sampled on a [w] x [h] grid, lightly averaged against noise. */
    private fun lumaAt(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val bw = bmp.width
        val bh = bmp.height
        if (pixels.size != bw * bh) pixels = IntArray(bw * bh)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = (y * bh / h).coerceIn(0, bh - 2)
            for (x in 0 until w) {
                val sx = (x * bw / w).coerceIn(0, bw - 2)
                val i = sy * bw + sx
                out[y * w + x] = (lum(pixels[i]) + lum(pixels[i + 1]) + lum(pixels[i + bw]) + lum(pixels[i + bw + 1])) / 4f
            }
        }
        return out
    }

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) / 65280f

    fun close() {
        runCatching { segmenter.close() }
    }

    companion object {
        private const val TAG = "PersonSegmenter"
        /** Temporal smoothing of still edges: higher is steadier. Moving edges are not smoothed. */
        private const val SMOOTHING = 0.75f
        const val MODEL_HQ = "selfie_multiclass_256x256.tflite"
        const val MODEL_FAST = "selfie_segmenter.tflite"
    }
}
