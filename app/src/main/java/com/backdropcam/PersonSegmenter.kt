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
class PersonSegmenter(context: Context, highQuality: Boolean) {

    class Mask(val data: ByteArray, val width: Int, val height: Int)

    private val appContext = context.applicationContext
    private val model = if (highQuality) MODEL_HQ else MODEL_FAST
    private var usingGpu = true
    private var segmenter: ImageSegmenter = create()
    private var prev: FloatArray? = null
    private var lastTs = 0L

    /** Temporal smoothing: higher is steadier, lower reacts faster. */
    @Volatile var smoothing = 0.3f

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

    fun segment(bitmap: Bitmap): Mask? {
        if (resetRequested) {
            prev = null
            resetRequested = false
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

        val p = prev?.takeIf { it.size == n }
        val out = ByteArray(n)
        val a = smoothing
        for (i in 0 until n) {
            var v = if (invert) 1f - cur[i] else cur[i]
            if (p != null) v = a * p[i] + (1f - a) * v
            cur[i] = v
            out[i] = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        prev = cur
        return Mask(out, m.width, m.height)
    }

    fun close() {
        runCatching { segmenter.close() }
    }

    companion object {
        private const val TAG = "PersonSegmenter"
        const val MODEL_HQ = "selfie_multiclass_256x256.tflite"
        const val MODEL_FAST = "selfie_segmenter.tflite"
    }
}
