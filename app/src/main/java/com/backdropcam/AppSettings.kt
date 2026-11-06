package com.backdropcam

import android.content.Context

/** Small wrapper around SharedPreferences. */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Older or low-memory phones: start at 1080p with the fast cut-out model, which they can run
     * smoothly. The user can still pick higher settings themselves.
     */
    val lowEndPhone: Boolean = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        am.isLowRamDevice || mem.totalMem < 5L * 1024 * 1024 * 1024 ||
            android.os.Build.VERSION.SDK_INT < 29 || Runtime.getRuntime().availableProcessors() < 6
    }

    /** "max", "1080" or "720" */
    var quality: String
        get() = p.getString("quality", if (lowEndPhone) "1080" else "max")!!
        set(v) = p.edit().putString("quality", v).apply()

    /**
     * "Best edges" model. Off by default: the fast model keeps up with every frame on every phone,
     * so the cut-out stays exactly on you without slowing the video down.
     */
    var highQualityCutout: Boolean
        get() = p.getBoolean("hq2", false)
        set(v) = p.edit().putBoolean("hq2", v).apply()

    var softness: Float
        get() = p.getFloat("softness", 0.3f)
        set(v) = p.edit().putFloat("softness", v).apply()

    var mirrorFront: Boolean
        get() = p.getBoolean("mirror", true)
        set(v) = p.edit().putBoolean("mirror", v).apply()

    /**
     * Upload inside the app (needs the app registered with Google). Off = hand the video to the
     * Google Drive app, which works on every phone with no setup.
     */
    var driveInApp: Boolean
        get() = p.getBoolean("driveInApp", false)
        set(v) = p.edit().putBoolean("driveInApp", v).apply()

    var autoUpload: Boolean
        get() = p.getBoolean("autoUpload", false)
        set(v) = p.edit().putBoolean("autoUpload", v).apply()

    var micOn: Boolean
        get() = p.getBoolean("micOn", true)
        set(v) = p.edit().putBoolean("micOn", v).apply()

    /** 1.0 = 100 % */
    var micGain: Float
        get() = p.getFloat("micGain", 1f)
        set(v) = p.edit().putFloat("micGain", v).apply()

    /** Countdown seconds: 0, 3 or 10 */
    var timer: Int
        get() = p.getInt("timer", 0)
        set(v) = p.edit().putInt("timer", v).apply()

    var frontCamera: Boolean
        get() = p.getBoolean("front", false)
        set(v) = p.edit().putBoolean("front", v).apply()

    /** "none", "blur" or a backdrop file name */
    var backdrop: String
        get() = p.getString("backdrop", "blur")!!
        set(v) = p.edit().putString("backdrop", v).apply()

    /** Set once the app has eased its settings for a slow phone (so it only happens once). */
    var slowPhoneHandled: Boolean
        get() = p.getBoolean("slowPhoneHandled", false)
        set(v) = p.edit().putBoolean("slowPhoneHandled", v).apply()

    var slow720Handled: Boolean
        get() = p.getBoolean("slow720Handled", false)
        set(v) = p.edit().putBoolean("slow720Handled", v).apply()

    var slowModelHandled: Boolean
        get() = p.getBoolean("slowModelHandled", false)
        set(v) = p.edit().putBoolean("slowModelHandled", v).apply()

    /** True while the camera screen is open; still true at the next start means the app crashed. */
    var sessionOpen: Boolean
        get() = p.getBoolean("sessionOpen", false)
        set(v) { p.edit().putBoolean("sessionOpen", v).commit() }

    /** Lightest settings, after a crash: 720p, fast model on the processor. */
    var safeMode: Boolean
        get() = p.getBoolean("safeMode", false)
        set(v) = p.edit().putBoolean("safeMode", v).apply()

    fun enterSafeMode() {
        p.edit()
            .putBoolean("safeMode", true)
            .putString("quality", "720")
            .putBoolean("hq2", false)
            .putBoolean("sessionOpen", false)
            .commit()
    }

    var firstRun: Boolean
        get() = p.getBoolean("firstRun", true)
        set(v) = p.edit().putBoolean("firstRun", v).apply()

    var lastVideo: String?
        get() = p.getString("lastVideo", null)
        set(v) = p.edit().putString("lastVideo", v).apply()

    /** Back camera: use the ultra-wide (zoomed out) lens when the phone has one. */
    var wideStart: Boolean
        get() = p.getBoolean("wideStart", true)
        set(v) = p.edit().putBoolean("wideStart", v).apply()

    /** "full" (fill the tall screen), "169" (9:16 / 16:9), "11" (square) or "43" (widest view). */
    var viewMode: String
        get() = p.getString("viewMode", "full")!!
        set(v) = p.edit().putString("viewMode", v).apply()

    val fullScreen: Boolean get() = viewMode == "full"

    /** Person size over a backdrop, 0.5..1 (1 = as filmed). New key so everyone starts at 100 %. */
    var personScale: Float
        get() = p.getFloat("personSize", 1f)
        set(v) = p.edit().putFloat("personSize", v).apply()

    var personOffX: Float
        get() = p.getFloat("personOffX", 0f)
        set(v) = p.edit().putFloat("personOffX", v).apply()

    var personOffY: Float
        get() = p.getFloat("personOffY", 0f)
        set(v) = p.edit().putFloat("personOffY", v).apply()

    /** -1..1 (0 = unchanged) */
    var brightness: Float
        get() = p.getFloat("brightness", 0f)
        set(v) = p.edit().putFloat("brightness", v).apply()


    /** Seconds, 0 = off */
    var autoStop: Int
        get() = p.getInt("autoStop", 0)
        set(v) = p.edit().putInt("autoStop", v).apply()

    var teleOn: Boolean
        get() = p.getBoolean("teleOn", true)
        set(v) = p.edit().putBoolean("teleOn", v).apply()

    var teleSpeed: Int
        get() = p.getInt("teleSpeed", 4)
        set(v) = p.edit().putInt("teleSpeed", v).apply()

    var teleSize: Int
        get() = p.getInt("teleSize", 28)
        set(v) = p.edit().putInt("teleSize", v).apply()

    var teleScript: String?
        get() = p.getString("teleScript", null)
        set(v) = p.edit().putString("teleScript", v).apply()

    /** H.265/HEVC for about half the file size; off = H.264 for very old players. */
    var smallFiles: Boolean
        get() = p.getBoolean("smallFiles", true)
        set(v) = p.edit().putBoolean("smallFiles", v).apply()

    val qualityCap: Int
        get() = when (quality) {
            "1080" -> 1920
            "720" -> 1280
            else -> 3840
        }
}

/**
 * Where the logo sits, in the visible picture: centre (0..1, y up), width as a fraction of the
 * picture width, rotation in degrees (counter-clockwise), opacity 0..1, and behind the person.
 */
data class LogoPlacement(
    val x: Float = 0.80f,
    val y: Float = 0.88f,
    val size: Float = 0.28f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val behind: Boolean = false,
) {
    override fun toString() = "$x,$y,$size,$rotation,$opacity,$behind"

    companion object {
        fun parse(s: String): LogoPlacement? = runCatching {
            val p = s.split(",")
            LogoPlacement(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toBoolean())
        }.getOrNull()
    }
}
