package com.backdropcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

/**
 * Runs MediaPipe person segmentation on low-resolution analysis frames and hands
 * a smoothed person-probability mask (0..255, upright orientation) to [onMask].
 *
 * The mask is small (256x256) and is up-sampled with bilinear filtering on the GPU,
 * while the camera image itself is always composited at full camera resolution.
 */
class PersonSegmenter(
    context: Context,
    private val onMask: (ByteArray, Int, Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private val segmenter: ImageSegmenter = create(context)
    private var prev: FloatArray? = null
    private var tsMs = 0L

    /** Temporal smoothing factor: higher = steadier edges, lower = less lag. */
    var smoothing = 0.55f

    private fun create(context: Context): ImageSegmenter {
        fun build(delegate: Delegate) = ImageSegmenter.createFromOptions(
            context,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .setDelegate(delegate)
                        .build()
                )
                .setRunningMode(RunningMode.VIDEO)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
        )
        return try {
            build(Delegate.GPU)
        } catch (t: Throwable) {
            build(Delegate.CPU)
        }
    }

    /** Forget the previous mask, e.g. after switching camera. */
    fun reset() {
        prev = null
    }

    override fun analyze(image: ImageProxy) {
        try {
            val rotation = image.imageInfo.rotationDegrees
            val raw = image.toBitmap()
            val upright = if (rotation == 0) raw else {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true
                )
            }
            val mpImage = BitmapImageBuilder(upright).build()
            tsMs += 33
            val result = segmenter.segmentForVideo(mpImage, tsMs)
            val masks = result.confidenceMasks()
            if (masks.isPresent && masks.get().isNotEmpty()) {
                // selfie_segmenter: index 0 = background, 1 = person when two masks are returned.
                val list = masks.get()
                val m = if (list.size >= 2) list[1] else list[0]
                val fb = ByteBufferExtractor.extract(m).asFloatBuffer()
                val n = m.width * m.height
                val cur = FloatArray(n)
                fb.get(cur, 0, n)
                val p = prev
                val out = ByteArray(n)
                val a = smoothing
                for (i in 0 until n) {
                    val v = if (p != null && p.size == n) a * p[i] + (1f - a) * cur[i] else cur[i]
                    cur[i] = v
                    out[i] = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
                }
                prev = cur
                onMask(out, m.width, m.height)
            }
            if (upright !== raw) raw.recycle()
            upright.recycle()
        } finally {
            image.close()
        }
    }

    fun close() = segmenter.close()

    companion object {
        /**
         * Swap this file in app/src/main/assets for a different MediaPipe segmentation model
         * (for example deeplab_v3.tflite) to trade speed for accuracy.
         */
        const val MODEL_ASSET = "selfie_segmenter.tflite"
    }
}
