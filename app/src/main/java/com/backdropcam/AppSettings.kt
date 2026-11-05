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
        get() = p.getFloat("softness", 0.3f)
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

    /** Spoken framing guidance plus "start" / "stop" voice commands. */
    var handsFree: Boolean
        get() = p.getBoolean("handsFree", false)
        set(v) = p.edit().putBoolean("handsFree", v).apply()

    /** Back camera: use the ultra-wide (zoomed out) lens when the phone has one. */
    var wideStart: Boolean
        get() = p.getBoolean("wideStart", true)
        set(v) = p.edit().putBoolean("wideStart", v).apply()

    /** "full" (fill the tall screen), "169" (standard) or "43" (widest view the lens gives). */
    var viewMode: String
        get() = p.getString("viewMode", "full")!!
        set(v) = p.edit().putString("viewMode", v).apply()

    val fullScreen: Boolean get() = viewMode == "full"

    /** Person size, 0.5..1 */
    var personScale: Float
        get() = p.getFloat("personScale", 0.85f)
        set(v) = p.edit().putFloat("personScale", v).apply()

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

    var warmth: Float
        get() = p.getFloat("warmth", 0f)
        set(v) = p.edit().putFloat("warmth", v).apply()

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

    val qualityCap: Int
        get() = when (quality) {
            "1080" -> 1920
            "720" -> 1280
            else -> 3840
        }
}
