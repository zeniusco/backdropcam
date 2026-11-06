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

    var highQualityCutout: Boolean
        get() = p.getBoolean("hq", !lowEndPhone)
        set(v) = p.edit().putBoolean("hq", v).apply()

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
            .putBoolean("hq", false)
            .putBoolean("sessionOpen", false)
            .commit()
    }

    var firstRun: Boolean
        get() = p.getBoolean("firstRun", true)
        set(v) = p.edit().putBoolean("firstRun", v).apply()

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

    /** Logo overlay switched on (needs a logo image saved in the app). */
    var logoOn: Boolean
        get() = p.getBoolean("logoOn", true)
        set(v) = p.edit().putBoolean("logoOn", v).apply()

    /** Logo position per backdrop ("none", "blur" or a backdrop file), so each can differ. */
    fun logoPlacement(backdrop: String): LogoPlacement =
        p.getString("logo_$backdrop", null)?.let(LogoPlacement::parse) ?: LogoPlacement()

    fun setLogoPlacement(backdrop: String, v: LogoPlacement) = p.edit().putString("logo_$backdrop", v.toString()).apply()

    /** H.265/HEVC for about half the file size; off = H.264 for very old players. */
    var smallFiles: Boolean
        get() = p.getBoolean("smallFiles", true)
        set(v) = p.edit().putBoolean("smallFiles", v).apply()

    // ---------------------------------------------------------------- music

    var musicOn: Boolean
        get() = p.getBoolean("musicOn", false)
        set(v) = p.edit().putBoolean("musicOn", v).apply()

    /** 0..1 */
    var musicVolume: Float
        get() = p.getFloat("musicVolume", 0.3f)
        set(v) = p.edit().putFloat("musicVolume", v).apply()

    var musicDuck: Boolean
        get() = p.getBoolean("musicDuck", true)
        set(v) = p.edit().putBoolean("musicDuck", v).apply()

    var musicName: String
        get() = p.getString("musicName", "")!!
        set(v) = p.edit().putString("musicName", v).apply()

    // ---------------------------------------------------------------- brand kit

    var brandName: String
        get() = p.getString("brandName", "")!!
        set(v) = p.edit().putString("brandName", v).apply()

    var brandRole: String
        get() = p.getString("brandRole", "")!!
        set(v) = p.edit().putString("brandRole", v).apply()

    var brandColor: Int
        get() = p.getInt("brandColor", OverlayComposer.DEFAULT_COLOR)
        set(v) = p.edit().putInt("brandColor", v).apply()

    /** Show the name title (lower third) on the video. */
    var nameTitleOn: Boolean
        get() = p.getBoolean("nameTitleOn", false)
        set(v) = p.edit().putBoolean("nameTitleOn", v).apply()

    /** Show an intro card for the first seconds of every recording. */
    var introOn: Boolean
        get() = p.getBoolean("introOn", false)
        set(v) = p.edit().putBoolean("introOn", v).apply()

    var introTitle: String
        get() = p.getString("introTitle", "")!!
        set(v) = p.edit().putString("introTitle", v).apply()

    /** Brand settings and logo positions, for sharing a kit with the team. */
    fun exportBrand(): org.json.JSONObject {
        val o = org.json.JSONObject()
            .put("brandName", brandName).put("brandRole", brandRole).put("brandColor", brandColor)
            .put("nameTitleOn", nameTitleOn).put("introOn", introOn).put("introTitle", introTitle)
        val logos = org.json.JSONObject()
        for ((k, v) in p.all) if (k.startsWith("logo_") && v is String) logos.put(k, v)
        o.put("logoPlacements", logos)
        return o
    }

    fun importBrand(o: org.json.JSONObject) {
        val e = p.edit()
        e.putString("brandName", o.optString("brandName", brandName))
        e.putString("brandRole", o.optString("brandRole", brandRole))
        e.putInt("brandColor", o.optInt("brandColor", brandColor))
        e.putBoolean("nameTitleOn", o.optBoolean("nameTitleOn", nameTitleOn))
        e.putBoolean("introOn", o.optBoolean("introOn", introOn))
        e.putString("introTitle", o.optString("introTitle", introTitle))
        o.optJSONObject("logoPlacements")?.let { logos ->
            for (k in logos.keys()) if (k.startsWith("logo_")) e.putString(k, logos.getString(k))
        }
        e.apply()
    }

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
