package com.backdropcam

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/** Camera lens discovery and resolution choice. */
object CameraLenses {

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun id(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    /** Selects exactly the camera with this id (used for the separate ultra-wide camera). */
    fun selectorFor(cameraId: String): CameraSelector =
        CameraSelector.Builder().addCameraFilter { list -> list.filter { id(it) == cameraId } }.build()

    /** Horizontal field of view in radians, from the lens focal length and sensor size. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun fieldOfView(info: CameraInfo): Float {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0f
        val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        return 2f * atan(max(sensor.width, sensor.height) / (2f * focal))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun isBack(info: CameraInfo) =
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK

    /**
     * Finds a back camera clearly wider than the main one, e.g. the 0.6x ultra-wide.
     * Returns it with its zoom factor relative to the main camera.
     */
    fun findUltraWide(infos: List<CameraInfo>): Pair<CameraInfo, Float>? {
        val main = CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).firstOrNull() ?: return null
        val mainFov = fieldOfView(main)
        if (mainFov <= 0f) return null
        val best = infos.filter { it != main && isBack(it) }.maxByOrNull { fieldOfView(it) } ?: return null
        val fov = fieldOfView(best)
        if (fov < mainFov * 1.2f) return null
        return best to (tan(mainFov / 2f) / tan(fov / 2f))
    }

    /**
     * The largest camera mode of the wanted shape (16:9 or 4:3), up to [cap] pixels on the long
     * side, that this phone's video encoder can also record.
     */
    fun resolutionSelector(cap: Int, fourByThree: Boolean): ResolutionSelector {
        val ratio = if (fourByThree) 4f / 3f else 16f / 9f
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (fourByThree) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .setResolutionFilter { sizes, _ ->
                val withinCap = sizes.filter { max(it.width, it.height) <= cap }.sortedByDescending { it.width * it.height }
                val good = withinCap.filter {
                    val r = max(it.width, it.height).toFloat() / minOf(it.width, it.height)
                    abs(r - ratio) < 0.02f && VideoRecorder.isSizeSupported(it.width, it.height)
                }
                good.ifEmpty { withinCap }.ifEmpty { sizes }
            }
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
    }
}
