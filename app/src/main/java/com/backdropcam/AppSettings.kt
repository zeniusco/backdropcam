package com.backdropcam

import android.content.Context

/** Small wrapper around SharedPreferences. */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "max", "1080" or "720" */
    var quality: String
        get() = p.getString("quality", "max")!!
        set(v) = p.edit().putString("quality", v).apply()

    var highQualityCutout: Boolean
        get() = p.getBoolean("hq", true)
        set(v) = p.edit().putBoolean("hq", v).apply()

    var softness: Float
        get() = p.getFloat("softness", 0.4f)
        set(v) = p.edit().putFloat("softness", v).apply()

    var mirrorFront: Boolean
        get() = p.getBoolean("mirror", true)
        set(v) = p.edit().putBoolean("mirror", v).apply()

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

    var lastVideo: String?
        get() = p.getString("lastVideo", null)
        set(v) = p.edit().putString("lastVideo", v).apply()

    /** Spoken framing guidance, "say start" and "clap twice to stop". */
    var handsFree: Boolean
        get() = p.getBoolean("handsFree", false)
        set(v) = p.edit().putBoolean("handsFree", v).apply()

    /** Open the camera at its widest (zoomed out) view. */
    var wideStart: Boolean
        get() = p.getBoolean("wideStart", true)
        set(v) = p.edit().putBoolean("wideStart", v).apply()

    val qualityCap: Int
        get() = when (quality) {
            "1080" -> 1920
            "720" -> 1280
            else -> 3840
        }
}
