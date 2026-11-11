package com.backdropcam

import android.content.Context

/** Small wrapper around SharedPreferences. */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Older or low-memory phones: start at 1080p with the fast cut-out model, which they can run
     * smoothly. The user can still pick higher settings themselves.
     */
    /**
     * How much memory the phone has: 0 = low (under about 4.5 GB, or Android's "low RAM" flag),
     * 1 = mid (under about 7 GB), 2 = high. The screen preview is lightened on low and mid phones.
     */
    val memoryTier: Int = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mem.totalMem / (1024.0 * 1024 * 1024)
        when {
            am.isLowRamDevice || gb < 4.5 -> 0
            gb < 7.0 -> 1
            else -> 2
        }
    }

    val lowEndPhone: Boolean = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        am.isLowRamDevice || mem.totalMem < 5L * 1024 * 1024 * 1024 ||
            android.os.Build.VERSION.SDK_INT < 29 || Runtime.getRuntime().availableProcessors() < 6
    }

    /**
     * "max", "1080" or "720". Starts at 1080p (720p on weak phones): 4K means 4 times the work for
     * every frame, which made recording and the whole app lag. 4K stays one tap away.
     */
    var quality: String
        get() = p.getString("quality2", if (lowEndPhone) "720" else "1080")!!
        set(v) = p.edit().putString("quality2", v).apply()

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
        get() = p.getBoolean("driveInApp2", true)
        set(v) = p.edit().putBoolean("driveInApp2", v).apply()

    /** Name put at the start of video file names (Name_BackdropKit_date_time.mp4). */
    var personName: String
        get() = p.getString("personName", "")!!
        set(v) = p.edit().putString("personName", v).apply()

    var autoUpload: Boolean
        get() = p.getBoolean("autoUpload", false)
        set(v) = p.edit().putBoolean("autoUpload", v).apply()

    var micOn: Boolean
        get() = p.getBoolean("micOn", true)
        set(v) = p.edit().putBoolean("micOn", v).apply()

    /** Remove background noise (fans, traffic, hum) from recordings, keeping the voice. Off by default. */
    var voiceIsolation: Boolean
        get() = p.getBoolean("voiceIsolation", false)
        set(v) = p.edit().putBoolean("voiceIsolation", v).apply()

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

    /**
     * Called when the camera screen closes normally after being used for a while. Safe mode is
     * a precaution, not a life sentence: after three trouble-free sessions the normal (faster,
     * sharper) cut-out comes back. Only if the app then crashes again does safe mode stay.
     */
    fun noteCleanSession() {
        // Weak phones stay in safe mode: it is what keeps them stable.
        if (!safeMode || lowEndPhone || memoryTier < 2) return
        val runs = p.getInt("safeCleanRuns", 0) + 1
        val e = p.edit().putInt("safeCleanRuns", runs)
        if (runs >= 3 && p.getInt("safeModeEntries", 0) < 2) e.putBoolean("safeMode", false)
        e.apply()
    }

    fun enterSafeMode() {
        p.edit()
            .putInt("safeModeEntries", p.getInt("safeModeEntries", 0) + 1)
            .putInt("safeCleanRuns", 0)
            .putBoolean("safeMode", true)
            .putString("quality2", "720")
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

    /** H.265/HEVC for about half the file size; off = H.264 for very old players. */
    var smallFiles: Boolean
        get() = p.getBoolean("smallFiles", true)
        set(v) = p.edit().putBoolean("smallFiles", v).apply()

    val qualityCap: Int
        get() = when (quality) {
            "1080" -> 1920
            "720" -> 1280
            // 4K needs far more memory than low-memory phones have (it made them crash).
            else -> if (memoryTier == 0) 1920 else 3840
        }
}

