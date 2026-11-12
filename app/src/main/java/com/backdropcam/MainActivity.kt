package com.backdropcam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import com.backdropcam.databinding.ActivityMainBinding
import com.backdropcam.databinding.DialogTrimBinding
import com.backdropcam.databinding.SheetRemoteBinding
import com.backdropcam.databinding.SheetMicBinding
import com.backdropcam.databinding.SheetResultBinding
import com.backdropcam.databinding.SheetSettingsBinding
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The camera screen. The heavy lifting lives elsewhere:
 *  - [CompositeEngine]: camera -> cut-out -> backdrop, on the GPU, to the screen and the encoder
 *  - [VideoRecorder]:   encodes and saves the MP4
 * This class wires the UI to them.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var engine: CompositeEngine
    private lateinit var backdrops: BackdropStore

    private val io = Executors.newSingleThreadExecutor()
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Camera
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var wideCam: Pair<CameraInfo, Float>? = null
    private var usingWideCam = false
    private var boundFourByThree = false
    private var torchOn = false
    private var deviceRotation = 0
    private var cropX = 1f
    private var cropY = 1f
    private var foreground = false

    // Recording
    private var recorder: VideoRecorder? = null
    private var countdown: CountDownTimer? = null
    private var countdownTone: ToneGenerator? = null
    private var recDotBlink: ObjectAnimator? = null
    @Volatile private var micGain = 1f
    @Volatile private var micMuted = false
    private var micMonitor: MicMonitor? = null

    // Adjust panel
    private var adjustOpen = false
    private var lastFraming: CompositeEngine.Framing? = null
    private var backdropMean: FloatArray? = null

    // Result / Drive upload
    private var lastVideo: Uri? = null
    private var lastLink: String? = null
    private var uploading = false
    private var uploadProgress = 0
    private var uploadMessage: String? = null
    private var resultUi: SheetResultBinding? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) bindCamera()
            else MaterialAlertDialogBuilder(this)
                .setTitle("Camera access needed")
                .setMessage("BackdropKit records with the camera, so it cannot work without it. Allow Camera (and Microphone for sound) in the app's settings.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivitySafe(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
        }


    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addBackdrop(uri) }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val outcome = runCatching {
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken
            }
            val token = outcome.getOrNull()
            when {
                token != null -> upload(token)
                else -> driveSetupProblem(outcome.exceptionOrNull())
            }
        }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        settings = AppSettings(this)
        CrashGuard.install(this)
        PerfLog.begin(this)
        // The camera screen was still open when the app ended. Android's own record says whether
        // that was a real problem or the app was simply closed (then nothing needs to change).
        val exit = ExitInfo.last(this)
        val crashedLastTime = settings.sessionOpen && (exit == null || exit.abnormal)
        if (settings.sessionOpen && !crashedLastTime) settings.sessionOpen = false
        if (crashedLastTime) {
            lastExit = exit
            // Out of memory or a freeze: lighter picture settings. Anything else: safe mode too.
            if (exit?.memoryOrFreeze == true) settings.enterLightSettings() else settings.enterSafeMode()
            // A crash below the app (or one Android kept no record of): never use the graphics
            // chip for the cut-out again in this version of the app.
            if (exit == null || exit.nativeCrash) settings.gpuBlockedVersion = appVersionCode()
        }
        backdrops = BackdropStore(this)
        micGain = settings.micGain
        lastVideo = settings.lastVideo?.let(Uri::parse)

        engine = CompositeEngine(this).apply {
            onGeometry = { w, h -> onCameraGeometry(w, h) }
            onFraming = { f ->
                lastFraming = f
                warnIfHeadCut(f)
            }
            onTooSlow = { lightMode = true; PerfLog.add("too slow: easing settings"); easeForSlowPhone() }
            onPreviewLost = { restorePreviewSurface() }
            lightMode = settings.lowEndPhone || settings.safeMode
        }
        applyEngineOptions()
        loadSegmenter()

        setupWindow()
        setupPreviewSurface()
        setupGestures()
        setupButtons()
        setupAdjustPanel()

        updateMicUi()
        updateTimerUi()
        ui.aspectChip.text = viewModeLabel()
        buildBackdropStrip()
        applyBackdropSelection()
        lastVideo?.let { loadThumbInto(it, ui.lastThumb, forgetIfMissing = true) }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.all { granted(it) }) bindCamera() else permissionLauncher.launch(needed.toTypedArray())

        if (crashedLastTime) main.postDelayed({ showCrashNotice() }, 800)
        if (settings.firstRun) {
            settings.firstRun = false
            main.postDelayed({
                hint("Welcome. Pick a backdrop below (or Add your own), then press the red button to record.")
            }, 1200)
        }
        watchTemperature()
    }

    /**
     * The phone cannot keep up with the cut-out: switch to the fast model first, then (if still
     * slow) to 1080p. Never while recording, and each step only once.
     */
    private fun easeForSlowPhone() {
        if (recorder != null || countdown != null) return
        when {
            settings.highQualityCutout && !settings.slowModelHandled -> {
                settings.slowModelHandled = true
                settings.highQualityCutout = false
                applyEngineOptions()
                loadSegmenter()
                engine.resetSpeedCheck()
                hint("This phone was struggling, so the cut-out switched to Fast for smooth video. You can change it in Settings.")
            }
            settings.quality == "max" && !settings.slowPhoneHandled -> {
                settings.slowPhoneHandled = true
                settings.quality = "1080"
                engine.resetSpeedCheck()
                bindCamera()
                hint("Switched to 1080p so this phone keeps up. Tap the quality chip to change it.")
            }
            settings.quality != "720" && !settings.slow720Handled -> {
                settings.slow720Handled = true
                settings.slowPhoneHandled = true
                settings.quality = "720"
                engine.resetSpeedCheck()
                bindCamera()
                hint("Switched to 720p so this phone keeps up. Tap the quality chip to change it.")
            }
        }
    }

    private var lastExit: ExitInfo.Last? = null

    /** After a crash: the app restarts in lighter settings; offer to share the error report. */
    private fun showCrashNotice() {
        val exit = lastExit
        val javaReport = CrashGuard.lastReport(this)
        val report = buildReport(exit, javaReport)
        val safe = settings.safeMode
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("BackdropKit closed unexpectedly")
            .setMessage(
                (exit?.summary?.takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") +
                    "It has restarted with lighter settings for this phone: 720p and Fast cut-out" +
                    (if (safe) ", with the cut-out running on the processor instead of the graphics chip" else "") +
                    ". You can raise them again in Settings.\n\nSending the error report helps fix the cause."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Send report") { _, _ -> sendReport(report) }
        b.show()
        CrashGuard.clear(this)
    }

    private fun sendReport(report: String) {
        startActivitySafe(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "BackdropKit error report")
                    .putExtra(Intent.EXTRA_TEXT, report),
                "Send report"
            )
        )
    }

    /**
     * Every 5 seconds: one line in the running record about the drawing thread (how long each
     * step took, how long it kept new work waiting) and one about the phone (memory, heat,
     * whether this screen itself was held up). This is what shows a slow-down building up.
     */
    private var stateTicks = 0
    private var stateDueMs = 0L
    private val stateTick = object : Runnable {
        override fun run() {
            if (!foreground) return
            val now = android.os.SystemClock.uptimeMillis()
            val uiLate = if (stateDueMs == 0L) 0L else now - stateDueMs
            stateDueMs = now + 5000
            main.postDelayed(this, 5000)
            engine.windowStats { PerfLog.add(it) }
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val heat = if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1 else -1
            PerfLog.add(
                "ui late $uiLate | ${if (recorder != null) "REC" else "idle"} q ${settings.quality} ${settings.viewMode} " +
                    "${if (settings.frontCamera) "front" else "back"} bd ${backdropKind()} | ram free ${mi.availMem / (1024 * 1024)}" +
                    "${if (mi.lowMemory) " LOW" else ""} | java ${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} " +
                    "native ${android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} | heat $heat | $cutoutWhere"
            )
            val n = ++stateTicks
            // The slower measurements (total and graphics memory, threads, open files) every
            // 30 seconds, and the record is saved every 15, both off this thread.
            if (n % 6 == 1 || n % 3 == 0) io.execute {
                if (n % 6 == 1) runCatching {
                    val m = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                    PerfLog.add(
                        "mem total ${m.totalPss / 1024} graphics ${m.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024)} " +
                            "native ${m.getMemoryStat("summary.native-heap")?.toIntOrNull()?.div(1024)} | " +
                            "threads ${File("/proc/self/task").list()?.size} files ${File("/proc/self/fd").list()?.size}"
                    )
                }
                PerfLog.save(this@MainActivity)
            }
        }
    }

    private fun backdropKind() = when {
        settings.backdrop == KEY_NONE -> "none"
        settings.backdrop == KEY_BLUR -> "blur"
        backdrops.isVideo(settings.backdrop) -> "video"
        else -> "picture"
    }

    /** The error report: phone, why the app last ended (if it did), and the running record. */
    private fun buildReport(exit: ExitInfo.Last?, javaReport: String?): String = buildString {
        val mem = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        append("BackdropKit ").append(runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()).append('\n')
        append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ")
        append("memory tier ${settings.memoryTier}, app memory limit $mem MB, safe mode ${settings.safeMode}\n\n")
        if (exit != null) append(exit.details).append('\n')
        if (javaReport != null) append("Error:\n").append(javaReport.take(6000)).append("\n\n")
        append("This session:\n").append(PerfLog.dump(70)).append('\n')
        val prev = PerfLog.previous(this@MainActivity, 70)
        if (prev.isNotBlank()) append("Previous session:\n").append(prev).append('\n')
    }.take(45000)

    // ---- light check: "too dark" comes from the camera's exposure (ISO x time), which measures the
    // room's light. The person's skin tone does not change it, so it cannot fire falsely for
    // darker-skinned people the way a "how bright is the face" check would.
    private var exposureAvg = 0f
    private var darkSinceMs = 0L
    private var lastDarkHintMs = 0L

    private fun onExposure(e: Float) {
        exposureAvg = if (exposureAvg == 0f) e else exposureAvg * 0.8f + e * 0.2f
        val now = System.currentTimeMillis()
        if (exposureAvg < DARK_EXPOSURE) {
            darkSinceMs = 0L
            return
        }
        if (darkSinceMs == 0L) darkSinceMs = now
        // Only when it stays dark for a few seconds, never while recording, at most every 90 s.
        if (now - darkSinceMs < 3000 || recorder != null || countdown != null || now - lastDarkHintMs < 90_000) return
        lastDarkHintMs = now
        hint("It's quite dark here. Face a window or a lamp, or turn on more lights, for a sharper video and a cleaner cut-out.")
    }

    private var statsView: TextView? = null
    private val statsTick = object : Runnable {
        override fun run() {
            val v = statsView ?: return
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val appMb = ((rt.totalMemory() - rt.freeMemory()) + android.os.Debug.getNativeHeapAllocatedSize()) / (1024 * 1024)
            v.text = engine.statsText.ifBlank { "Measuring…" } +
                "\nphone memory free ${mi.availMem / (1024 * 1024)} MB${if (mi.lowMemory) " (LOW)" else ""}  ·  app $appMb MB" +
                "\ncut-out on $cutoutWhere" + (if (settings.safeMode) "  ·  safe mode" else "")
            main.postDelayed(this, 500)
        }
    }

    private fun toggleStats() {
        val existing = statsView
        if (existing != null) {
            ui.root.removeView(existing)
            statsView = null
            main.removeCallbacks(statsTick)
            return
        }
        val v = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = ui.topBar.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                marginStart = dp(12)
                topMargin = dp(4)
            }
        }
        ui.root.addView(v)
        statsView = v
        main.post(statsTick)
        hint("Speed readout on. Long-press the quality button again to hide it.")
    }

    /** Long 4K recordings make phones hot; say so before the phone itself slows down. */
    private fun watchTemperature() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        var warned = false
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE && !warned) {
                warned = true
                hint("The phone is getting hot. Let it cool down, or record in 1080p to ease the load.")
            } else if (status <= PowerManager.THERMAL_STATUS_LIGHT) {
                warned = false
            }
        }
        if (runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener) }.isSuccess) {
            thermalListener = listener
        }
    }

    /** Kept so it can be removed again when the screen closes (otherwise it leaks the screen). */
    private var thermalListener: Any? = null

    /** Free space on the storage where videos are saved, in MB (-1 if unknown). */
    private fun freeSpaceMb(): Long = runCatching {
        val dir = getExternalFilesDir(null) ?: filesDir
        StatFs(dir.path).availableBytes / (1024 * 1024)
    }.getOrDefault(-1L)

    override fun onResume() {
        super.onResume()
        settings.sessionOpen = true
        foreground = true
        resumedAtMs = System.currentTimeMillis()
        main.removeCallbacks(stateTick)
        stateDueMs = 0L
        main.postDelayed(stateTick, 5000)
        PerfLog.add("screen opened")
        // Camera access may have been allowed in the phone's Settings while the app was away.
        if (!cameraStarted && granted(Manifest.permission.CAMERA)) bindCamera()
        if (playbackPauses == 0) engine.setBackdropVideoPlaying(true)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    override fun onPause() {
        super.onPause()
        settings.sessionOpen = false
        foreground = false
        main.removeCallbacks(stateTick)
        PerfLog.add("screen left")
        io.execute { PerfLog.save(this) }
        // A session that ran for a while without trouble counts towards leaving safe mode.
        if (System.currentTimeMillis() - resumedAtMs > 30_000) settings.noteCleanSession()
        engine.setBackdropVideoPlaying(false)
        orientationListener.disable()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.unregisterListener(tiltListener)
        cancelCountdown()
        if (recorder != null) stopRecording()
        micMonitor?.stop()
        micMonitor = null
    }

    override fun onDestroy() {
        super.onDestroy()
        remote?.stop()
        probeConn?.let { runCatching { unbindService(it) } }
        probeConn = null
        if (Build.VERSION.SDK_INT >= 29) (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { l ->
            runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
        }
        engine.release()
        // The executors are left running: a save or upload that is still in flight finishes on
        // its own, and its result is dropped because the activity is gone.
    }

    /** Volume buttons and Bluetooth selfie remotes start / stop recording. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event.repeatCount == 0) onRecordPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupWindow() {
        // Full-screen camera: system bars hidden, swipe from an edge to show them.
        WindowInsetsControllerCompat(window, ui.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ui.topBar.updatePadding(top = bars.top + dp(10))
            ui.bottomArea.updatePadding(bottom = bars.bottom + dp(8))
            insets
        }
        ui.root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) applyCrop()
        }
    }

    private fun setupPreviewSurface() {
        // Lighter screen preview on phones with less memory: the preview is drawn at a lower
        // resolution and the phone's display hardware scales it up for free. The recording is
        // not affected (it is drawn separately, at full quality).
        val previewLongSide = when (settings.memoryTier) { 0 -> 960; 1 -> 1280; else -> 0 }
        if (previewLongSide > 0) {
            ui.surface.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
                val w = r - l
                val h = b - t
                if (w <= 0 || h <= 0 || (w == or - ol && h == ob - ot)) return@addOnLayoutChangeListener
                val s = previewLongSide.toFloat() / max(w, h)
                if (s < 1f) ui.surface.holder.setFixedSize((w * s).roundToInt() and 1.inv(), (h * s).roundToInt() and 1.inv())
                else ui.surface.holder.setSizeFromLayout()
            }
        }
        // While recording on a low-memory phone, the screen shows every second frame so the
        // phone keeps up; the video itself still gets every frame.
        engine.previewEveryNthWhileRecording = if (settings.memoryTier == 0) 2 else 1
        ui.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                surfaceW = w; surfaceH = h; surfaceAlive = true
                engine.setPreviewSurface(holder.surface, w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceAlive = false
                engine.clearPreviewSurface()
            }
        })
    }

    private var surfaceW = 0
    private var surfaceH = 0
    private var surfaceAlive = false
    private var lastRestoreMs = 0L

    /** The screen stopped showing new pictures: hand its surface to the engine again. */
    private fun restorePreviewSurface() {
        val now = System.currentTimeMillis()
        if (!surfaceAlive || !foreground || isFinishing || now - lastRestoreMs < 1500) return
        val surface = ui.surface.holder.surface ?: return
        if (!surface.isValid || surfaceW == 0) return
        lastRestoreMs = now
        PerfLog.add("screen surface set up again")
        engine.setPreviewSurface(surface, surfaceW, surfaceH)
    }

    private fun setupButtons() {
        ui.settingsBtn.setOnClickListener { showSettings() }
        // Long-press: send a report (how the app has been running, this session and the last).
        ui.settingsBtn.setOnLongClickListener {
            sendReport(buildReport(lastExit, null))
            true
        }
        ui.flashBtn.setOnClickListener { toggleTorch() }
        ui.aspectChip.setOnClickListener { cycleViewMode() }
        ui.qualityChip.setOnClickListener { cycleQuality() }
        // Long-press: show or hide a small speed readout (frames per second, cut-out time).
        ui.qualityChip.setOnLongClickListener { toggleStats(); true }
        ui.timerChip.setOnClickListener { cycleTimer() }
        ui.micBtn.setOnClickListener { onMicPressed() }
        ui.tuneBtn.setOnClickListener { setAdjustOpen(!adjustOpen) }
        ui.remoteBtn.setOnClickListener { showRemoteSheet() }
        ui.recordBtn.setOnClickListener { onRecordPressed() }
        ui.pauseBtn.setOnClickListener { togglePause() }
        ui.flipBtn.setOnClickListener { flipCamera() }
        ui.lastThumb.setOnClickListener { lastVideo?.let { showResultSheet(it) } ?: hint("No recordings yet") }
        ui.lastThumb.setOnLongClickListener {
            // All recordings live in the gallery under Movies/BackdropKit.
            startActivitySafe(
                Intent(Intent.ACTION_VIEW).setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            )
            true
        }
        ui.lastThumb.roundCorners(dp(14))
    }

    // How far the phone leans back: 0 = standing upright, 1 = lying flat. Measured from gravity.
    @Volatile private var flatness = 0f
    private var pendingRotation = -1
    private var pendingSinceMs = 0L

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
            val g = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
            flatness = kotlin.math.abs(z) / g
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                // Lying flat or leaning far back (on a table, held low): the reading is unreliable,
                // so keep the current orientation instead of flipping the picture sideways.
                if (flatness > 0.75f) return
                val target = when {
                    o in 60..120 -> 90
                    o in 150..210 -> 180
                    o in 240..300 -> 270
                    o <= 30 || o >= 330 -> 0
                    else -> return // dead zone avoids flicker between orientations
                }
                // Only turn once the new way of holding the phone has lasted half a second.
                val now = System.currentTimeMillis()
                if (target != pendingRotation) {
                    pendingRotation = target
                    pendingSinceMs = now
                    return
                }
                if (now - pendingSinceMs < 500) return
                if (target != deviceRotation && recorder == null) {
                    deviceRotation = target
                    engine.setDeviceRotation(target)
                    rotateControls(target)
                    ui.aspectChip.text = viewModeLabel()
                }
            }
        }
    }

    // ================================================================== camera

    private var cameraStarted = false
    private var resumedAtMs = 0L

    private fun bindCamera() {
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            val front = settings.frontCamera
            val infos = provider.availableCameraInfos
            wideCam = if (front) null else runCatching { CameraLenses.findUltraWide(infos) }.getOrNull()
            val wide = wideCam
            usingWideCam = !front && settings.wideStart && wide != null
            val selector = when {
                front -> CameraSelector.DEFAULT_FRONT_CAMERA
                usingWideCam && wide != null -> CameraLenses.selectorFor(CameraLenses.id(wide.first))
                else -> CameraSelector.DEFAULT_BACK_CAMERA
            }
            val info = runCatching { selector.filter(infos).firstOrNull() }.getOrNull()
            if (info == null) {
                if (front) {
                    settings.frontCamera = false
                    bindCamera()
                } else hint("No camera found")
                return@addListener
            }
            val fourByThree = settings.viewMode == "43"
            val builder = Preview.Builder()
                .setResolutionSelector(CameraLenses.resolutionSelector(settings.qualityCap, fourByThree))
                .setTargetRotation(Surface.ROTATION_0)
            runCatching { CameraLenses.allowLowLightExposure(builder, info) }
            runCatching { CameraLenses.watchExposure(builder) { e -> main.post { onExposure(e) } } }
            val p = builder.build()
            p.setSurfaceProvider(engine.surfaceProvider(front, info.sensorRotationDegrees))
            try {
                camera = provider.bindToLifecycle(this, selector, p)
                preview = p
            } catch (e: Exception) {
                hint("Camera error: ${e.message}")
                return@addListener
            }
            torchOn = false
            boundFourByThree = fourByThree
            setupCameraControls()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCameraGeometry(w: Int, h: Int) {
        applyCrop()
        val longSide = max(w, h)
        ui.qualityChip.text = when {
            longSide >= 3840 -> "4K"
            longSide >= 2560 -> "1440p"
            longSide >= 1920 -> "1080p"
            longSide >= 1280 -> "720p"
            else -> "${minOf(w, h)}p"
        }
    }

    private fun setupCameraControls() {
        val cam = camera ?: return
        // INVISIBLE (not GONE) keeps the top bar symmetric on cameras without a torch.
        ui.flashBtn.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.INVISIBLE
        ui.flashBtn.setImageResource(R.drawable.ic_flash_off)
        setActive(ui.flashBtn, false)
        ui.zoomRow.removeAllViews()

        var checkZoomOut = false
        if (!settings.frontCamera) {
            val wc = wideCam
            if (wc != null) {
                // A separate ultra-wide camera: switching lens re-opens the camera.
                addLensChip(zoomLabel(wc.second), usingWideCam) {
                    if (!usingWideCam && !busy()) { settings.wideStart = true; bindCamera() }
                }
                addLensChip("1x", !usingWideCam) {
                    if (usingWideCam && !busy()) { settings.wideStart = false; bindCamera() }
                }
            } else {
                checkZoomOut = true // maybe the main camera itself can zoom out below 1x
            }
        }
        updateLensRowVisibility()

        cam.cameraInfo.zoomState.removeObservers(this)
        if (checkZoomOut) cam.cameraInfo.zoomState.observe(this) { zs ->
            if (camera !== cam || ui.zoomRow.childCount > 0) return@observe
            val min = zs.minZoomRatio
            if (min < 0.95f) {
                if (settings.wideStart) cam.cameraControl.setZoomRatio(min)
                addLensChip(zoomLabel(min), settings.wideStart) {
                    settings.wideStart = true
                    cam.cameraControl.setZoomRatio(min)
                    highlightLens(0)
                }
                addLensChip("1x", !settings.wideStart) {
                    settings.wideStart = false
                    cam.cameraControl.setZoomRatio(1f)
                    highlightLens(1)
                }
                updateLensRowVisibility()
            }
        }
        rotateControls(deviceRotation)
    }

    private fun zoomLabel(z: Float) = if (z < 1f) String.format("%.1fx", z) else "${z.roundToInt()}x"

    private fun addLensChip(label: String, active: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            minWidth = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
            setOnClickListener { onClick() }
        }
        styleLensChip(tv, active)
        ui.zoomRow.addView(tv)
        rotateControls(deviceRotation)
    }

    private fun styleLensChip(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        tv.background = if (active) GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        } else null
    }

    private fun highlightLens(index: Int) {
        for (i in 0 until ui.zoomRow.childCount) {
            (ui.zoomRow.getChildAt(i) as? TextView)?.let { styleLensChip(it, i == index) }
        }
    }

    private fun updateLensRowVisibility() {
        ui.zoomRow.visibility = if (ui.zoomRow.childCount > 0 && !adjustOpen) View.VISIBLE else View.GONE
    }

    /** FULL fills the screen (the video is cropped to the screen's shape); 16:9 and 4:3 show it all. */
    private fun applyCrop() {
        val nw = engine.naturalWidth
        val nh = engine.naturalHeight
        val rw = ui.root.width
        val rh = ui.root.height
        if (nw == 0 || nh == 0 || rw == 0 || rh == 0) return
        val lp = ui.surface.layoutParams as ConstraintLayout.LayoutParams
        if (settings.fullScreen) {
            val screen = rw.toFloat() / rh
            val cameraAspect = nw.toFloat() / nh
            if (screen < cameraAspect) { cropX = screen / cameraAspect; cropY = 1f } else { cropX = 1f; cropY = cameraAspect / screen }
            lp.dimensionRatio = "$rw:$rh"
        } else if (settings.viewMode == "11") {
            // Square: the centre of the portrait picture (stays square when turned sideways).
            val side = minOf(nw, nh).toFloat()
            cropX = side / nw; cropY = side / nh
            lp.dimensionRatio = "1:1"
        } else {
            cropX = 1f; cropY = 1f
            lp.dimensionRatio = "$nw:$nh"
        }
        engine.setCrop(cropX, cropY)
        ui.surface.layoutParams = lp
        ui.aspectChip.text = viewModeLabel()
    }

    private fun viewModeLabel() = when (settings.viewMode) {
        "169" -> if (deviceRotation % 180 == 0) "9:16" else "16:9"
        "11" -> "1:1"
        "43" -> "4:3"
        else -> "FULL"
    }

    /** Tap to step through the picture shapes (no menu): FULL, 16:9, 4:3, 1:1. */
    private fun cycleViewMode() {
        if (busy()) return
        val next = when (settings.viewMode) {
            "full" -> "169"
            "169" -> "43"
            "43" -> "11"
            else -> "full"
        }
        setViewMode(next)
        hint(
            when (next) {
                "169" -> "16:9, the standard video shape"
                "43" -> "4:3, the widest view this lens can see"
                "11" -> "1:1, square for Instagram and LinkedIn"
                else -> "Full screen, the video matches your screen"
            }
        )
    }

    private fun setViewMode(mode: String) {
        settings.viewMode = mode
        ui.aspectChip.text = viewModeLabel()
        if ((mode == "43") != boundFourByThree) bindCamera() else applyCrop()
    }

    private fun cycleQuality() {
        if (busy()) return
        settings.quality = when (settings.quality) {
            "max" -> "1080"
            "1080" -> "720"
            // Low-memory phones cannot run 4K (it crashed them), so they step between 1080p and 720p.
            else -> if (settings.memoryTier == 0) "1080" else "max"
        }
        hint(
            when (settings.quality) {
                "1080" -> "1080p"
                "720" -> "720p, smaller files"
                else -> "Highest quality this camera offers"
            }
        )
        bindCamera()
    }

    private fun flipCamera() {
        if (busy()) return
        settings.frontCamera = !settings.frontCamera
        bindCamera()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        ui.flashBtn.setImageResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        setActive(ui.flashBtn, torchOn)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (adjustOpen) {
                    settings.personScale = (settings.personScale * d.scaleFactor).coerceIn(0.5f, 1f)
                    if (adjustTab != AdjustTab.SIZE) selectAdjustTab(AdjustTab.SIZE, explain = false)
                    else ui.adjustSlider.value = settings.personScale * 100f
                    updateAdjustValue()
                    applyPersonAndColor()
                    return true
                }
                val cam = camera ?: return false
                val cur = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                cam.cameraControl.setZoomRatio(cur * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e2.pointerCount > 1) return false
                val dx = -distanceX / ui.surface.width
                val dy = -distanceY / ui.surface.height
                when {
                    adjustOpen -> movePerson(dx, dy)
                    else -> return false
                }
                return true
            }
        })
        ui.surface.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) tap.onTouchEvent(e)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val p = preview ?: return
        val pt = engine.previewToCameraPoint(x / ui.surface.width, y / ui.surface.height) ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f, p).createPoint(pt[0], pt[1])
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
        )
        showFocusRing(ui.surface.left + x, ui.surface.top + y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val r = ui.focusRing
        r.animate().cancel()
        r.translationX = x - r.width / 2f
        r.translationY = y - r.height / 2f
        r.alpha = 1f
        r.scaleX = 1.4f
        r.scaleY = 1.4f
        r.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(220).withEndAction {
            r.animate().setStartDelay(700).alpha(0f).setDuration(300).start()
        }.start()
    }

    // ================================================================== cut-out, backdrops, adjust panel

    private fun appVersionCode(): Long = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    private fun gpuKey(hq: Boolean) =
        "${if (hq) "hq" else "fast"}_${appVersionCode()}_${Build.FINGERPRINT.hashCode()}"

    /** Where the cut-out runs right now, for the readout and the error report. */
    @Volatile private var cutoutWhere = "processor"

    /**
     * Loads the cut-out model. It runs on the graphics chip only once a check in a separate
     * process has shown that this works on this phone (on some phones it crashes the whole
     * app). Until then, and wherever it does not work, it runs on the processor.
     */
    private fun loadSegmenter() {
        val hq = settings.highQualityCutout
        val key = gpuKey(hq)
        val blocked = settings.safeMode || settings.gpuBlockedVersion == appVersionCode()
        val state = if (blocked) 2 else settings.gpuState(key)
        val gpu = state == 1
        // Little memory: let go of the old model before loading the new one.
        if (settings.memoryTier < 2) engine.closeSegmenter()
        io.execute {
            try {
                val seg = PersonSegmenter(this, hq, allowGpu = gpu)
                cutoutWhere = (if (seg.onGpu) "graphics chip" else "processor") +
                    when { blocked -> " (graphics chip switched off)"; state == 0 -> " (graphics chip not checked yet)"; state == 2 -> " (graphics chip does not work here)"; else -> "" }
                engine.setSegmenter(seg)
            } catch (e: Throwable) {
                main.post { hint("Could not load the cut-out model") }
            }
        }
        if (state == 0) main.postDelayed({ probeGpu(hq, key) }, 2500)
    }

    private var probeConn: android.content.ServiceConnection? = null

    private fun probeGpu(hq: Boolean, key: String) {
        if (probeConn != null || isFinishing || isDestroyed || settings.gpuState(key) != 0) return
        // Two tries that never gave an answer count as "does not work".
        if (settings.gpuAttempts(key) >= 2) return settings.setGpuState(key, 2)
        settings.countGpuAttempt(key)
        var done = false
        lateinit var conn: android.content.ServiceConnection
        val timeout = Runnable {
            if (!done) {
                done = true
                probeConn = null
                runCatching { unbindService(conn) }
            }
        }
        fun finish(ok: Boolean) {
            if (done) return
            done = true
            probeConn = null
            main.removeCallbacks(timeout)
            runCatching { unbindService(conn) }
            settings.setGpuState(key, if (ok) 1 else 2)
            // Works: switch the cut-out over to the graphics chip now.
            if (!isFinishing && !isDestroyed && settings.highQualityCutout == hq) {
                if (ok && recorder == null && countdown == null) loadSegmenter()
                else if (!ok) cutoutWhere = "processor (graphics chip does not work here)"
            }
        }
        conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                val msg = android.os.Message.obtain(null, if (hq) 1 else 0)
                msg.replyTo = android.os.Messenger(Handler(Looper.getMainLooper()) { m -> finish(m.what == 1); true })
                try {
                    android.os.Messenger(binder).send(msg)
                } catch (e: Exception) {
                    finish(false)
                }
            }
            // The helper process ended without answering: the graphics chip crashed it.
            override fun onServiceDisconnected(name: android.content.ComponentName?) = finish(false)
            override fun onBindingDied(name: android.content.ComponentName?) = finish(false)
            override fun onNullBinding(name: android.content.ComponentName?) = finish(false)
        }
        val bound = runCatching {
            bindService(Intent(this, GpuProbeService::class.java), conn, BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            runCatching { unbindService(conn) }
            return
        }
        probeConn = conn
        main.postDelayed(timeout, 20_000)
    }

    private fun applyEngineOptions() {
        engine.setOptions(settings.mirrorFront, settings.highQualityCutout, settings.softness)
    }

    private fun buildBackdropStrip() {
        val strip = ui.backdropStrip
        strip.removeAllViews()
        strip.addView(stripTile(KEY_NONE, null, R.drawable.ic_block, "None"))
        strip.addView(stripTile(KEY_BLUR, null, R.drawable.ic_blur_on, "Blur"))
        strip.addView(stripTile(KEY_ADD, null, R.drawable.ic_add, "Add"))
        for (f in backdrops.list()) strip.addView(stripTile(f.name, f, 0, null))
        highlightSelection()
        rotateControls(deviceRotation)
    }

    /** A square tile: a backdrop image, or an icon with a small label (None / Blur / Add). */
    private fun stripTile(key: String, file: File?, icon: Int, label: String?): View {
        val tile = FrameLayout(this).apply {
            tag = key
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(10) }
            background = ContextCompat.getDrawable(context, R.drawable.bg_item)
            contentDescription = label ?: "Backdrop"
            roundCorners(dp(16))
        }
        if (file != null) {
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tile.addView(iv)
            if (backdrops.isVideo(file.name)) {
                tile.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_play_arrow)
                    background = ContextCompat.getDrawable(context, R.drawable.bg_circle_dark)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.BOTTOM).apply {
                        setMargins(0, 0, dp(4), dp(4))
                    }
                })
            }
            io.execute {
                val t = backdrops.thumbnail(file)
                main.post { iv.setImageBitmap(t) }
            }
        } else {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            col.addView(ImageView(this).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
            col.addView(TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(0xCCFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
            tile.addView(col)
        }
        tile.setOnClickListener {
            if (key == KEY_ADD) {
                if (!busy()) pickBackdrop.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            } else {
                settings.backdrop = key
                applyBackdropSelection()
            }
        }
        if (file != null) tile.setOnLongClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove this backdrop?")
                .setPositiveButton("Remove") { _, _ ->
                    backdrops.delete(key)
                    if (settings.backdrop == key) settings.backdrop = KEY_BLUR
                    buildBackdropStrip()
                    applyBackdropSelection()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        return tile
    }

    private fun highlightSelection() {
        val sel = settings.backdrop
        for (i in 0 until ui.backdropStrip.childCount) {
            val v = ui.backdropStrip.getChildAt(i) as FrameLayout
            v.foreground = if (v.tag == sel) ContextCompat.getDrawable(this, R.drawable.fg_selected) else null
        }
    }

    private fun applyBackdropSelection() {
        highlightSelection()
        updateFramingNeed()
        when (val key = settings.backdrop) {
            KEY_NONE -> engine.setMode(CompositeEngine.MODE_NONE)
            KEY_BLUR -> engine.setMode(CompositeEngine.MODE_BLUR)
            else -> io.execute {
                val file = backdrops.file(key)
                if (backdrops.isVideo(key) && file.exists()) {
                    val frame = backdrops.videoFrame(file, 512)
                    backdropMean = frame?.let { ImageUtils.meanColor(it) }
                    val aspect = frame?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
                    engine.setBackdropVideo(file.absolutePath, aspect)
                    return@execute
                }
                // No larger than the phone can use: a full-size picture takes about 40 MB twice.
                val bmp = runCatching {
                    backdrops.load(key, when (settings.memoryTier) { 0 -> 1920; 1 -> 2560; else -> 4096 })
                }.getOrNull()
                if (bmp != null) {
                    backdropMean = ImageUtils.meanColor(bmp)
                    engine.setBackdrop(bmp)
                } else main.post {
                    settings.backdrop = KEY_BLUR
                    applyBackdropSelection()
                }
            }
        }
    }

    private fun addBackdrop(uri: Uri) {
        io.execute {
            try {
                val f = backdrops.add(uri)
                main.post {
                    settings.backdrop = f.name
                    buildBackdropStrip()
                    applyBackdropSelection()
                }
            } catch (e: Exception) {
                main.post { hint("Could not use that file: ${e.message}") }
            }
        }
    }

    private enum class AdjustTab { SIZE, LIGHT }

    private var adjustTab = AdjustTab.SIZE

    /**
     * One slider, two icon tabs (size / brightness). Keeps the panel short so it never
     * covers you while you watch the effect.
     */
    private fun setupAdjustPanel() {
        ui.adjustSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            when (adjustTab) {
                AdjustTab.SIZE -> settings.personScale = v / 100f
                AdjustTab.LIGHT -> settings.brightness = v / 100f
            }
            updateAdjustValue()
            applyPersonAndColor()
        }
        ui.tabSize.setOnClickListener { selectAdjustTab(AdjustTab.SIZE, explain = true) }
        ui.tabLight.setOnClickListener { selectAdjustTab(AdjustTab.LIGHT, explain = true) }
        ui.autoMatchBtn.setOnClickListener { autoMatchLight() }
        ui.resetAdjustBtn.setOnClickListener {
            settings.personScale = 1f; settings.personOffX = 0f; settings.personOffY = 0f
            settings.brightness = 0f
            selectAdjustTab(adjustTab, explain = false)
            applyPersonAndColor()
            hint("Size, position and light reset")
        }
        ui.doneAdjustBtn.setOnClickListener { setAdjustOpen(false) }
        selectAdjustTab(AdjustTab.SIZE, explain = false)
        applyPersonAndColor()
    }

    private fun selectAdjustTab(tab: AdjustTab, explain: Boolean) {
        adjustTab = tab
        val s = ui.adjustSlider
        // Change the range in an order that keeps the current value valid at every step.
        if (tab == AdjustTab.SIZE) {
            s.value = 75f
            s.valueFrom = 50f
            s.valueTo = 100f
            s.value = (settings.personScale * 100f).coerceIn(50f, 100f)
        } else {
            s.valueFrom = -100f
            s.valueTo = 100f
            s.value = (settings.brightness * 100f).coerceIn(-100f, 100f)
        }
        setActive(ui.tabSize, tab == AdjustTab.SIZE)
        setActive(ui.tabLight, tab == AdjustTab.LIGHT)
        updateAdjustValue()
        if (explain) hint(
            when (tab) {
                AdjustTab.SIZE -> "Size: make yourself smaller over the backdrop. Drag the picture to move."
                AdjustTab.LIGHT -> "Brightness of you (not the backdrop)"
            }
        )
    }

    private fun updateAdjustValue() {
        ui.adjustValue.text = when (adjustTab) {
            AdjustTab.SIZE -> "${(settings.personScale * 100f).roundToInt()}%"
            AdjustTab.LIGHT -> signed(settings.brightness)
        }
    }

    private fun signed(v: Float): String {
        val n = (v * 100f).roundToInt()
        return if (n > 0) "+$n" else "$n"
    }

    /** The "where are you in the picture" analysis only runs while something uses its result. */
    private fun updateFramingNeed() {
        engine.framingEnabled = adjustOpen || (hasBackdropImage() && settings.personScale < 0.99f)
    }

    private fun applyPersonAndColor() {
        updateFramingNeed()
        engine.setPersonTransform(settings.personScale, settings.personOffX, settings.personOffY)
        engine.setColorAdjust(2f.pow(settings.brightness), 0f)
    }

    /** The adjust panel replaces the lens switch and backdrop strip while open, to save space. */
    private fun setAdjustOpen(open: Boolean) {
        adjustOpen = open
        updateFramingNeed()
        ui.adjustPanel.visibility = if (open) View.VISIBLE else View.GONE
        ui.stripScroll.visibility = if (open) View.GONE else View.VISIBLE
        updateLensRowVisibility()
        setActive(ui.tuneBtn, open)
        if (open && !hasBackdropImage()) hint("Pick a backdrop image to resize or move yourself")
    }

    private var lastHeadHintMs = 0L

    /**
     * If you are shrunk over a backdrop but your head touches the top of what the camera sees,
     * the camera simply has no picture above it. Say so, once in a while.
     */
    private fun warnIfHeadCut(f: CompositeEngine.Framing) {
        if (!hasBackdropImage() || settings.personScale > 0.99f || recorder != null) return
        if (f.coverage < 0.03f || f.top > 0.02f) return
        val now = System.currentTimeMillis()
        if (now - lastHeadHintMs < 25_000) return
        lastHeadHintMs = now
        hint("Your head touches the top of the camera view. Step back a little, or set Size to 100%.")
    }

    private fun hasBackdropImage() = settings.backdrop != KEY_NONE && settings.backdrop != KEY_BLUR

    /**
     * A drag on the screen (fractions of the screen, y down) in picture axes (fractions, y up).
     * The picture is turned when the phone is sideways.
     */
    private fun screenToWorld(dxScreen: Float, dyScreen: Float): Pair<Float, Float> {
        val nx = dxScreen
        val ny = -dyScreen
        return when (deviceRotation) {
            90 -> ny to -nx
            180 -> -nx to -ny
            270 -> -ny to nx
            else -> nx to ny
        }
    }

    /** Moves the person by a drag on the screen (fractions of the screen, y down). */
    private fun movePerson(dxScreen: Float, dyScreen: Float) {
        val (wx, wy) = screenToWorld(dxScreen, dyScreen)
        settings.personOffX = (settings.personOffX + wx).coerceIn(-0.5f, 0.5f)
        settings.personOffY = (settings.personOffY + wy).coerceIn(-0.5f, 0.5f)
        applyPersonAndColor()
    }

    /** Sets your brightness so you match the backdrop's light. */
    private fun autoMatchLight() {
        val bg = backdropMean
        val f = lastFraming
        if (bg == null || !hasBackdropImage()) {
            return hint("Choose a backdrop image first")
        }
        if (f == null || f.coverage < 0.02f) return hint("Step into the frame first")
        fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b
        val lp = lum(f.meanR, f.meanG, f.meanB).coerceAtLeast(0.02f)
        val lb = lum(bg[0], bg[1], bg[2]).coerceAtLeast(0.02f)
        val gainValue = (lb / lp).pow(0.6f).coerceIn(0.6f, 1.7f)
        settings.brightness = (ln(gainValue) / ln(2f)).coerceIn(-1f, 1f)
        selectAdjustTab(AdjustTab.LIGHT, explain = false)
        applyPersonAndColor()
        hint("Matched to the backdrop's light")
    }

    // ================================================================== recording

    private fun busy(): Boolean {
        if (recorder != null || countdown != null) {
            hint("Not while recording")
            return true
        }
        return false
    }

    private fun onRecordPressed() {
        ui.recordBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when {
            recorder != null -> stopRecording()
            countdown != null -> cancelCountdown()
            settings.timer > 0 -> startCountdown(settings.timer)
            else -> startRecording()
        }
    }

    private fun startCountdown(seconds: Int) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        countdownTone = tone
        ui.countdownText.visibility = View.VISIBLE
        setShutter(true)
        countdown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                val n = ((msLeft + 999) / 1000).toString()
                ui.countdownText.text = n
                ui.countdownText.scaleX = 1.3f
                ui.countdownText.scaleY = 1.3f
                ui.countdownText.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }

            override fun onFinish() {
                tone?.release()
                countdownTone = null
                countdown = null
                ui.countdownText.visibility = View.GONE
                startRecording()
            }
        }.start()
    }

    private fun cancelCountdown() {
        if (countdown == null) return
        countdown?.cancel()
        countdown = null
        countdownTone?.release()
        countdownTone = null
        ui.countdownText.visibility = View.GONE
        if (recorder == null) setShutter(false)
    }

    private fun startRecording() {
        val nw = engine.naturalWidth
        val nh = engine.naturalHeight
        if (nw == 0 || nh == 0) return hint("Camera is not ready yet")
        val free = freeSpaceMb()
        if (free in 0..199) {
            setShutter(false)
            return hint("Not enough storage left to record ($free MB free). Free up some space first.")
        }
        if (free in 200..999) hint("Storage is getting low: about $free MB left")
        val rotation = deviceRotation
        val cw = (nw * cropX).roundToInt() and 1.inv()
        val ch = (nh * cropY).roundToInt() and 1.inv()
        val (ww, wh) = if (rotation % 180 == 0) cw to ch else ch to cw
        val size = VideoRecorder.pickEncoderSize(ww, wh)
        val audio = settings.micOn && granted(Manifest.permission.RECORD_AUDIO)
        if (settings.micOn && !audio) hint("Recording without sound: microphone access is not allowed for BackdropKit")

        micMonitor?.stop()
        micMonitor = null
        micMuted = false
        var created: VideoRecorder? = null
        try {
            val rec = VideoRecorder(
                this, size.width, size.height, audio,
                gain = { micGain },
                muted = { micMuted },
                onLevel = { level ->
                    lastMicLevel = level
                    main.post { ui.levelBar.progress = (level * 100).toInt() }
                },
                // In hands-free mode the recording's own sound is checked for "stop".
                preferHevc = settings.smallFiles,
                voiceIsolation = settings.voiceIsolation,
                personName = settings.personName,
            )
            created = rec
            rec.start()
            engine.startRecording(rec.inputSurface, size.width, size.height, rotation, { rec.ptsNs() }, { rec.paused || rec.problem != null })
            recorder = rec
            PerfLog.add("recording started ${size.width}x${size.height} ${rec.videoMime}")
        } catch (e: Exception) {
            // Give back what the failed start took (video encoder, microphone, the empty file).
            // Left behind, they would make every later recording slower or silent.
            PerfLog.error("start recording", e)
            created?.let { failed -> engine.stopRecording { io.execute { runCatching { failed.finish() } } } }
            setShutter(false)
            return hint("Cannot start recording: ${e.message}")
        }

        setShutter(true)
        ui.recPill.visibility = View.VISIBLE
        ui.recPill.alpha = 1f
        ui.quickRow.visibility = View.INVISIBLE
        ui.levelBar.visibility = if (audio) View.VISIBLE else View.GONE
        recDotBlink = ObjectAnimator.ofFloat(ui.recDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        ui.flipBtn.visibility = View.GONE
        ui.pauseBtn.visibility = View.VISIBLE
        ui.pauseBtn.setImageResource(R.drawable.ic_pause)
        setControlsEnabled(false)
        updateMicUi()
        tickTimer()
    }

    private val timerTick = Runnable { tickTimer() }

    private fun tickTimer() {
        val rec = recorder ?: return
        updateRecTime(rec)
        val limit = settings.autoStop
        if (limit > 0 && rec.ptsNs() / 1_000_000_000L >= limit) {
            stopRecording()
            return
        }
        // Stop by itself, and say why, as soon as the video can no longer be written. Otherwise
        // the timer would keep running while nothing is being saved.
        // The drawing thread has been stuck waiting for the video encoder: shut the encoder down
        // so the picture runs again. What was recorded so far is kept.
        if (engine.encoderStuckMs() > 3000 && rec.problem == null) {
            PerfLog.add("encoder stuck ${engine.encoderStuckMs()} ms: shutting it down")
            rec.abort("The phone's video encoder stopped responding")
        }
        val early = when {
            rec.problem != null -> rec.problem
            // Checked every few seconds: stop while there is still room to finish the file.
            ++spaceCheckTicks % 12 == 0 && freeSpaceMb() in 0..79 -> "The phone's storage is full"
            // Android 10 and older cannot write video files larger than about 4 GB.
            Build.VERSION.SDK_INT < 30 && rec.bytesWritten > 3_800_000_000L -> "This Android version's largest video size (4 GB) was reached"
            else -> null
        }
        if (early != null) {
            stopRecording(early)
            return
        }
        // One timer only, however often recording is started (e.g. Retake straight after Stop).
        main.removeCallbacks(timerTick)
        main.postDelayed(timerTick, 250)
    }

    private fun updateRecTime(rec: VideoRecorder) {
        val s = rec.ptsNs() / 1_000_000_000L
        var t = String.format("%02d:%02d", s / 60, s % 60)
        val limit = settings.autoStop
        if (limit > 0) t += String.format(" / %02d:%02d", limit / 60, limit % 60)
        ui.recTime.text = if (rec.paused) "$t  Paused" else t
    }

    private fun togglePause() {
        val rec = recorder ?: return
        if (rec.paused) {
            rec.resume()
            recDotBlink?.resume()
            ui.pauseBtn.setImageResource(R.drawable.ic_pause)
        } else {
            rec.pause()
            recDotBlink?.pause()
            ui.pauseBtn.setImageResource(R.drawable.ic_play_arrow)
        }
        updateRecTime(rec)
    }

    private var spaceCheckTicks = 0

    /** [stoppedEarly]: why the app stopped the recording by itself, if it did. */
    private fun stopRecording(stoppedEarly: String? = null) {
        val rec = recorder ?: return
        recorder = null
        main.removeCallbacks(timerTick)
        PerfLog.add("recording stopped${if (stoppedEarly != null) ": $stoppedEarly" else ""}")
        setShutter(false)
        recDotBlink?.cancel()
        recDotBlink = null
        ui.recPill.visibility = View.GONE
        ui.quickRow.visibility = View.VISIBLE
        ui.pauseBtn.visibility = View.GONE
        ui.flipBtn.visibility = View.VISIBLE
        setControlsEnabled(true)
        updateMicUi()
        engine.stopRecording {
            io.execute {
                try {
                    val uri = rec.finish()
                    main.post { onVideoSaved(uri, rec, stoppedEarly ?: rec.problem) }
                } catch (e: Exception) {
                    main.post {
                        hint("Could not save the video: ${e.message}")
                    }
                }
            }
        }
    }

    private fun onVideoSaved(uri: Uri, rec: VideoRecorder, stoppedEarly: String? = null) {
        lastVideo = uri
        lastLink = null
        uploadMessage = null
        uploadProgress = 0
        settings.lastVideo = uri.toString()
        loadThumbInto(uri, ui.lastThumb)
        if (isFinishing || isDestroyed) return
        showResultSheet(
            uri,
            if (stoppedEarly != null) "Stopped early: $stoppedEarly. The video up to that point was saved."
            else "${rec.width} × ${rec.height} · Movies/BackdropKit"
        )
        if (stoppedEarly != null) hint("Recording stopped: $stoppedEarly")
        if (settings.autoUpload && settings.driveInApp) startUpload()
    }

    /** Red circle when idle; it shrinks into a rounded "stop" square while recording. */
    private fun setShutter(recording: Boolean) {
        ui.recordInner.background = ContextCompat.getDrawable(
            this, if (recording) R.drawable.shutter_recording else R.drawable.shutter_idle
        )
        val s = if (recording) 0.5f else 1f
        ui.recordInner.animate().scaleX(s).scaleY(s).setDuration(180).start()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.35f
        for (v in listOf(ui.settingsBtn, ui.lastThumb, ui.zoomRow)) {
            v.isEnabled = enabled
            v.alpha = alpha
        }
    }

    private fun cycleTimer() {
        settings.timer = when (settings.timer) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        updateTimerUi()
        hint(if (settings.timer == 0) "Timer off" else "Recording starts ${settings.timer}s after you press record")
    }

    private fun updateTimerUi() {
        val t = settings.timer
        ui.timerChip.text = if (t == 0) "" else "${t}s"
        ui.timerChip.setPaddingRelative(if (t == 0) dp(13) else 0, 0, 0, 0)
        ui.timerChip.setCompoundDrawablesRelativeWithIntrinsicBounds(if (t == 0) R.drawable.ic_timer_off else 0, 0, 0, 0)
        ui.timerChip.setTextColor(if (t == 0) 0xFFFFFFFF.toInt() else ContextCompat.getColor(this, R.color.accent))
        TextViewCompat.setCompoundDrawableTintList(ui.timerChip, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
    }

    // ================================================================== microphone

    private fun onMicPressed() {
        if (recorder != null) {
            micMuted = !micMuted
            updateMicUi()
            hint(if (micMuted) "Mic muted" else "Mic on")
        } else {
            showMicSheet()
        }
    }

    private fun updateMicUi() {
        val off = if (recorder != null) micMuted else !settings.micOn
        ui.micBtn.setImageResource(if (off) R.drawable.ic_mic_off else R.drawable.ic_mic)
        ImageViewCompat.setImageTintList(
            ui.micBtn,
            ColorStateList.valueOf(if (off) ContextCompat.getColor(this, R.color.record) else 0xFFFFFFFF.toInt())
        )
    }

    private fun showMicSheet() {
        val b = SheetMicBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        b.micSwitch.isChecked = settings.micOn
        b.isolationSwitch.isChecked = settings.voiceIsolation
        b.isolationSwitch.setOnCheckedChangeListener { _, on ->
            settings.voiceIsolation = on
            hint(if (on) "Voice isolation on" else "Voice isolation off: all sounds are recorded")
        }
        b.gainSlider.value = ((settings.micGain * 100f).coerceIn(0f, 200f) / 5f).roundToInt() * 5f
        b.gainValue.text = "${b.gainSlider.value.toInt()}%"
        b.micSwitch.setOnCheckedChangeListener { _, on ->
            settings.micOn = on
            updateMicUi()
        }
        b.gainSlider.addOnChangeListener { _, value, _ ->
            micGain = value / 100f
            settings.micGain = micGain
            b.gainValue.text = "${value.toInt()}%"
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            micMonitor = MicMonitor({ micGain }) { level ->
                main.post { b.levelMeter.setProgressCompat((level * 100).toInt(), true) }
            }.also { it.start() }
        }
        dlg.setOnDismissListener {
            micMonitor?.stop()
            micMonitor = null
        }
        dlg.show()
    }

    // ================================================================== settings

    private fun showSettings() {
        if (busy()) return
        val b = SheetSettingsBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)

        val stopIds = mapOf(
            0 to R.id.as0, 30 to R.id.as30, 60 to R.id.as60, 90 to R.id.as90,
            120 to R.id.as120, 150 to R.id.as150, 180 to R.id.as180
        )
        b.autoStopGroup.check(stopIds[settings.autoStop] ?: R.id.as0)
        b.autoStopGroup.setOnCheckedStateChangeListener { _, ids ->
            settings.autoStop = stopIds.entries.firstOrNull { it.value == ids.firstOrNull() }?.key ?: 0
        }

        b.personName.setText(settings.personName)
        b.personName.doAfterTextChanged { settings.personName = it.toString().trim() }
        b.smallFilesSwitch.isChecked = settings.smallFiles
        b.smallFilesSwitch.setOnCheckedChangeListener { _, on -> settings.smallFiles = on }
        b.mirrorSwitch.isChecked = settings.mirrorFront
        b.mirrorSwitch.setOnCheckedChangeListener { _, on ->
            settings.mirrorFront = on
            applyEngineOptions()
        }

        b.modelGroup.check(if (settings.highQualityCutout) R.id.mHq else R.id.mFast)
        b.modelGroup.setOnCheckedStateChangeListener { _, ids ->
            val hq = ids.firstOrNull() != R.id.mFast
            if (hq != settings.highQualityCutout) {
                settings.highQualityCutout = hq
                applyEngineOptions()
                loadSegmenter()
            }
        }

        b.softnessSlider.value = settings.softness.coerceIn(0f, 1f)
        b.softnessSlider.addOnChangeListener { _, v, _ ->
            settings.softness = v
            applyEngineOptions()
        }

        b.driveInAppSwitch.isChecked = settings.driveInApp
        b.driveInAppSwitch.setOnCheckedChangeListener { _, on ->
            settings.driveInApp = on
            b.autoUploadSwitch.isEnabled = on
        }
        b.autoUploadSwitch.isEnabled = settings.driveInApp
        b.autoUploadSwitch.isChecked = settings.autoUpload
        b.autoUploadSwitch.setOnCheckedChangeListener { _, on -> settings.autoUpload = on }

        dlg.show()
    }

    // ================================================================== result sheet and Google Drive

    private fun showResultSheet(uri: Uri, details: String? = null) {
        val b = SheetResultBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        resultUi = b
        b.details.text = details ?: "Movies/BackdropKit"
        b.thumb.roundCorners(dp(16))
        loadThumbInto(uri, b.thumb)
        b.playBtn.setOnClickListener { playVideo(uri) }
        b.shareVideoBtn.setOnClickListener { shareVideo(uri) }
        b.uploadBtn.setOnClickListener {
            if (settings.driveInApp) startUpload() else shareVideo(uri)
        }
        b.trimBtn.setOnClickListener { showTrimDialog(uri, dlg) }
        b.retakeBtn.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete this video and record again?")
                .setMessage("The video will be removed from your gallery.")
                .setPositiveButton("Delete and retake") { _, _ ->
                    dlg.dismiss()
                    deleteVideo(uri)
                    hint("Deleted. Recording again")
                    onRecordPressed()
                }
                .setNegativeButton("Keep it", null)
                .show()
        }
        b.copyLinkBtn.setOnClickListener { lastLink?.let { copyLink(it) } }
        b.shareLinkBtn.setOnClickListener { lastLink?.let { shareText(it) } }
        dlg.setOnDismissListener { if (resultUi === b) resultUi = null }
        refreshResultUi()
        dlg.show()
    }

    /**
     * Plays a recording inside the app, so it works on every phone whatever gallery app is
     * installed. "Open in…" still hands it to another app.
     */
    private fun playVideo(uri: Uri) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val video = android.widget.VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        val controls = android.widget.MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        fun topButton(label: String, gravity: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = ContextCompat.getDrawable(context, R.drawable.bg_pill_dark)
            setOnClickListener { onClick() }
            root.addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity
            ).apply { setMargins(dp(16), dp(40), dp(16), 0) })
        }
        topButton("Close", Gravity.TOP or Gravity.START) { dlg.dismiss() }
        topButton("Open in…", Gravity.TOP or Gravity.END) {
            startActivitySafe(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Open with"
                )
            )
        }
        // Start first, then show the controls, so the button shows Pause from the first moment.
        video.setOnPreparedListener { video.start(); controls.show(3000) }
        video.setOnCompletionListener { controls.show(0) }
        video.setOnErrorListener { _, _, _ ->
            hint("This phone could not play the video here. Try Open in…")
            true
        }
        root.setOnClickListener { if (controls.isShowing) controls.hide() else controls.show(3000) }
        video.setVideoURI(uri)
        dlg.setContentView(root)
        dlg.setOnDismissListener {
            video.stopPlayback()
            // Back to the camera, which is laid out for portrait (it turns its own picture).
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            pauseCameraForPlayback(false)
        }
        pauseCameraForPlayback(true)
        // While watching, the screen turns with the phone (if auto-rotate is on), so landscape
        // videos fill the screen.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        dlg.show()
    }

    private var playbackPauses = 0

    /**
     * While a recording is played back (or previewed for trimming) the camera, the cut-out and
     * the backdrop video are switched off: they would only waste battery and memory.
     */
    private fun pauseCameraForPlayback(pause: Boolean) {
        if (pause) {
            if (playbackPauses++ > 0) return
            engine.setBackdropVideoPlaying(false)
            runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
            camera = null
        } else {
            if (playbackPauses == 0 || --playbackPauses > 0) return
            engine.setBackdropVideoPlaying(true)
            bindCamera()
        }
    }

    /** Removes a recording this app made (no permission prompt needed for our own files). */
    private fun deleteVideo(uri: Uri) {
        io.execute { runCatching { contentResolver.delete(uri, null, null) } }
        if (lastVideo == uri) {
            lastVideo = null
            lastLink = null
            settings.lastVideo = null
            ui.lastThumb.setImageDrawable(null)
        }
    }

    private fun showTrimDialog(uri: Uri, resultDialog: BottomSheetDialog) {
        val b = DialogTrimBinding.inflate(layoutInflater)
        io.execute {
            val durUs = Trimmer.durationUs(this, uri)
            main.post {
                if (durUs < 1_500_000L) return@post hint("This video is too short to trim")
                val total = durUs / 1_000_000f
                b.trimRange.valueFrom = 0f
                b.trimRange.valueTo = total
                b.trimRange.stepSize = 0f
                b.trimRange.values = listOf(0f, total)
                fun fmt(sec: Float) = String.format("%d:%04.1f", (sec / 60).toInt(), sec % 60)
                fun label() {
                    val (a, z) = b.trimRange.values
                    b.trimLabel.text = "${fmt(a)}  to  ${fmt(z)}   (${fmt(z - a)})"
                }
                // Live preview: the video jumps to whichever handle you drag; play shows the part
                // you keep, from the start handle to the end handle.
                val v = b.trimVideo
                var ready = false
                var playing = false
                val stopAtEnd = object : Runnable {
                    override fun run() {
                        if (!playing) return
                        val (_, z) = b.trimRange.values
                        if (v.currentPosition >= (z * 1000).toInt()) {
                            v.pause()
                            playing = false
                            b.trimPlay.visibility = View.VISIBLE
                            b.trimPlay.setImageResource(R.drawable.ic_play_arrow)
                        } else main.postDelayed(this, 50)
                    }
                }
                fun seek(sec: Float) {
                    if (!ready) return
                    if (playing) { v.pause(); playing = false; b.trimPlay.visibility = View.VISIBLE }
                    v.seekTo((sec * 1000).toInt())
                }
                v.setVideoURI(uri)
                v.setOnPreparedListener { mp ->
                    mp.setVolume(1f, 1f)
                    ready = true
                    // Start and pause at once so the first picture is drawn.
                    v.start(); v.pause(); v.seekTo(0)
                }
                v.setOnErrorListener { _, _, _ -> hint("Preview not available on this phone"); true }
                b.trimPlay.setOnClickListener {
                    if (!ready) return@setOnClickListener
                    if (playing) {
                        v.pause(); playing = false
                        b.trimPlay.setImageResource(R.drawable.ic_play_arrow)
                    } else {
                        val (a2, z2) = b.trimRange.values
                        val pos = v.currentPosition / 1000f
                        if (pos < a2 || pos >= z2 - 0.1f) v.seekTo((a2 * 1000).toInt())
                        v.start(); playing = true
                        b.trimPlay.setImageResource(R.drawable.ic_pause)
                        main.post(stopAtEnd)
                    }
                }
                v.setOnClickListener { b.trimPlay.performClick() }
                var lastA = 0f
                b.trimRange.addOnChangeListener { slider, _, _ ->
                    label()
                    val (a2, z2) = slider.values
                    seek(if (a2 != lastA) a2 else z2)
                    lastA = a2
                }
                label()
                pauseCameraForPlayback(true)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Trim video")
                    .setView(b.root)
                    .setPositiveButton("Save copy") { _, _ ->
                        val (a, z) = b.trimRange.values
                        hint("Trimming…")
                        io.execute {
                            try {
                                val out = Trimmer.trim(this, uri, (a * 1_000_000).toLong(), (z * 1_000_000).toLong())
                                main.post {
                                    resultDialog.dismiss()
                                    lastVideo = out
                                    lastLink = null
                                    uploadMessage = null
                                    settings.lastVideo = out.toString()
                                    loadThumbInto(out, ui.lastThumb)
                                    showResultSheet(out, "Trimmed copy · the original is still in your gallery")
                                }
                            } catch (e: Exception) {
                                main.post { hint("Could not trim: ${e.message}") }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener {
                        playing = false
                        main.removeCallbacks(stopAtEnd)
                        v.stopPlayback()
                        pauseCameraForPlayback(false)
                    }
                    .show()
            }
        }
    }

    private fun refreshResultUi() {
        val b = resultUi ?: return
        b.uploadBtn.isEnabled = !uploading
        b.uploadBtn.text = when {
            !settings.driveInApp -> "Share video"
            uploading -> "Uploading…"
            lastLink != null -> "Uploaded to Google Drive"
            else -> "Upload to Google Drive and get link"
        }
        b.uploadProgress.visibility = if (uploading) View.VISIBLE else View.GONE
        b.uploadProgress.setProgressCompat(uploadProgress, true)
        val msg = listOfNotNull(lastLink, uploadMessage).joinToString("\n\n").ifEmpty { null }
        b.uploadStatus.visibility = if (msg != null) View.VISIBLE else View.GONE
        b.uploadStatus.text = msg
        b.linkRow.visibility = if (lastLink != null) View.VISIBLE else View.GONE
    }

    private fun startUpload() {
        if (uploading || lastVideo == null) return
        uploading = true
        uploadProgress = 0
        uploadMessage = "Connecting to Google Drive…"
        refreshResultUi()
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    result.accessToken?.let { upload(it) } ?: uploadFinished(null, "Google sign-in failed")
                }
            }
            .addOnFailureListener { driveSetupProblem(it) }
    }

    /**
     * Google refused the sign-in. Almost always this means the app is not yet registered with
     * Google for Drive access (an OAuth client for this app's package and signing key).
     */
    private fun driveSetupProblem(e: Throwable?) {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        val why = when (code) {
            10 -> "Google does not recognise this app yet (code 10: no matching OAuth client for this app's signing key)."
            12501, 16 -> "The sign-in was closed before it finished."
            7 -> "No internet connection."
            null -> "Google closed the sign-in without giving access."
            else -> "Google refused the sign-in (code $code)."
        }
        if (code == 12501 || code == 16 || code == 7) {
            uploadFinished(null, "Could not connect to Google Drive. $why")
            return
        }
        // Google refused (the app is not registered with Google yet, or this account is not
        // allowed): open the normal Share menu instead, so the video can still be sent (Gmail's
        // "Send as Drive link", Drive, WhatsApp...). Next time it tries the upload again, so it
        // starts working by itself once the registration is in place.
        uploadFinished(null, null)
        lastVideo?.let { shareVideo(it) }
        hint("Could not upload from here ($why). Pick Gmail and Send as Drive link, or any other app.")
    }

    /** The normal Share menu for a video (Gmail, Drive, WhatsApp...). */
    private fun shareVideo(uri: Uri) {
        startActivitySafe(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share video"
            )
        )
    }

    /** Hands the video to the Google Drive app ("Save to Drive"); no setup needed. */
    private fun saveWithDriveApp(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val drive = Intent(send).setPackage(DRIVE_PACKAGE)
        // Drive closes its "Save" screen at once and uploads later in the background. The
        // permission that comes with the share ends when that screen closes, so the upload could
        // no longer read the video ("Synchronization failed"). Give Drive lasting permission to
        // read this one video, so the background upload can finish.
        runCatching { grantUriPermission(DRIVE_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try {
            startActivity(drive)
            hint("In Drive, tap Save. Then open the file there, tap Share, and copy the link.")
        } catch (e: Exception) {
            startActivitySafe(Intent.createChooser(send, "Save to Drive"))
        }
    }

    private fun upload(token: String) {
        val uri = lastVideo ?: return uploadFinished(null, null)
        uploadMessage = "Uploading…"
        refreshResultUi()
        uploadExecutor.execute {
            try {
                val up = DriveUploader(contentResolver, token).uploadAndShare(uri) { pct ->
                    main.post {
                        uploadProgress = pct
                        refreshResultUi()
                    }
                }
                main.post {
                    uploadFinished(
                        up.link,
                        if (up.public) null
                        else "Uploaded. Your Google account does not allow public links, so share the video from Google Drive with the people who should see it."
                    )
                }
            } catch (e: Exception) {
                main.post { uploadFinished(null, "Upload failed: ${e.message}") }
            }
        }
    }

    private fun uploadFinished(link: String?, message: String?) {
        uploading = false
        uploadMessage = message
        if (link != null) {
            lastLink = link
            copyLink(link)
            if (resultUi == null) hint(message ?: "Uploaded to Google Drive. Link copied.")
        } else if (message != null && resultUi == null) {
            hint(message)
        }
        refreshResultUi()
    }

    private fun copyLink(link: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Video link", link))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        startActivitySafe(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share link"))
    }

    // ================================================================== second-screen remote

    private var remote: RemoteServer? = null
    private val snapshotQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val remoteExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastMicLevel = 0f

    private fun startRemote(): RemoteServer? {
        remote?.let { return it }
        val server = RemoteServer(
            status = {
                val rec = recorder
                RemoteServer.Status(
                    recording = rec != null,
                    paused = rec?.paused == true,
                    seconds = (rec?.ptsNs() ?: 0L) / 1_000_000_000L,
                    countdown = countdown != null,
                    limit = settings.autoStop,
                    level = if (rec != null && !rec.paused) lastMicLevel else 0f,
                    quality = when (settings.quality) { "1080" -> "1080p"; "720" -> "720p"; else -> "Max quality" },
                    camera = if (settings.frontCamera) "Front camera" else "Back camera",
                )
            },
            onCommand = { a ->
                main.post {
                    // Only while the camera screen is open: a remote must never start the camera
                    // or a recording while the app is in the background, or during playback.
                    if (!foreground || playbackPauses > 0 || isFinishing) return@post
                    when (a) {
                        "record" -> onRecordPressed()
                        "pause" -> togglePause()
                    }
                }
            },
        )
        if (!server.start()) return null
        remote = server
        engine.snapshotWanted = { server.wantsFrames }
        engine.snapshotListener = listener@{ bmp ->
            // Skip this picture if the last one is still being compressed, so the laptop view
            // never falls behind the camera.
            // At most one picture may be waiting: otherwise copies pile up in memory whenever
            // compressing is slower than the camera.
            if (server.busy || !snapshotQueued.compareAndSet(false, true)) return@listener
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            remoteExecutor.execute {
                try {
                    server.pushFrame(copy)
                } finally {
                    copy.recycle()
                    snapshotQueued.set(false)
                }
            }
        }
        setActive(ui.remoteBtn, true)
        return server
    }

    private fun stopRemote() {
        engine.snapshotListener = null
        engine.snapshotWanted = { false }
        remote?.stop()
        remote = null
        setActive(ui.remoteBtn, false)
    }

    private fun showRemoteSheet() {
        val b = SheetRemoteBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        fun refresh() {
            val server = remote
            val url = server?.urls()?.firstOrNull()
            b.remoteSwitch.isChecked = server != null
            b.remoteQr.visibility = if (url != null) View.VISIBLE else View.GONE
            b.remoteShareBtn.visibility = if (url != null) View.VISIBLE else View.GONE
            b.remoteUrl.text = when {
                server == null -> ""
                url == null -> "Connect this phone to Wi-Fi (or turn on its hotspot), then open this again."
                else -> url
            }
            if (url != null) {
                io.execute {
                    val qr = runCatching { qrBitmap(url, 512) }.getOrNull()
                    main.post { b.remoteQr.setImageBitmap(qr) }
                }
                b.remoteShareBtn.setOnClickListener { shareText(url) }
            }
        }
        b.remoteSwitch.setOnCheckedChangeListener { _, on ->
            if (on && remote == null && startRemote() == null) hint("Could not start the remote")
            if (!on) stopRemote()
            refresh()
        }
        refresh()
        dlg.show()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ================================================================== helpers

    /** A short message over the camera picture (replaces system toasts). */
    private fun hint(message: String) {
        val v = ui.hintText
        v.text = message
        v.visibility = View.VISIBLE
        v.animate().cancel()
        v.animate().setStartDelay(0).alpha(1f).setDuration(150).start()
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, 2000L + message.length * 30L)
    }

    private val hideHint = Runnable {
        ui.hintText.animate().setStartDelay(0).alpha(0f).setDuration(250)
            .withEndAction { ui.hintText.visibility = View.GONE }.start()
    }

    /** Accent tint for a button whose feature is switched on. */
    private fun setActive(button: ImageView, on: Boolean) {
        ImageViewCompat.setImageTintList(
            button,
            ColorStateList.valueOf(if (on) ContextCompat.getColor(this, R.color.accent) else 0xFFFFFFFF.toInt())
        )
    }

    /** Keeps icons upright when the phone is turned, like the built-in camera app. */
    private fun rotateControls(rotation: Int) {
        val angle = when (rotation) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }
        // Every control turns in place so icons and text read upright. The top-bar toggles are
        // square, and the pill and hint are centred with room around them, so nothing clips.
        val views = mutableListOf<View>(
            ui.settingsBtn, ui.flashBtn, ui.aspectChip, ui.qualityChip, ui.timerChip,
            ui.micBtn, ui.tuneBtn,
            ui.pauseBtn, ui.flipBtn, ui.lastThumb, ui.countdownText, ui.recPill, ui.hintText,
            ui.tabSize, ui.tabLight, ui.adjustValue, ui.remoteBtn,
            ui.autoMatchBtn, ui.resetAdjustBtn, ui.doneAdjustBtn
        )
        for (i in 0 until ui.zoomRow.childCount) views += ui.zoomRow.getChildAt(i)
        for (i in 0 until ui.backdropStrip.childCount) views += ui.backdropStrip.getChildAt(i)
        for (v in views) v.animate().rotation(angle).setDuration(200).start()
    }

    private fun loadThumbInto(uri: Uri, target: ImageView, forgetIfMissing: Boolean = false) {
        io.execute {
            val r = MediaMetadataRetriever()
            val bmp: Bitmap? = try {
                r.setDataSource(this@MainActivity, uri)
                frameAt(r, 0L, 320)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
            main.post {
                when {
                    bmp != null -> target.setImageBitmap(bmp)
                    forgetIfMissing -> { // the video was deleted from the gallery
                        lastVideo = null
                        settings.lastVideo = null
                        target.setImageDrawable(null)
                    }
                }
            }
        }
    }

    /** A video frame no larger than [maxSide] pixels (a full 4K frame would waste 30 MB). */
    private fun frameAt(r: MediaMetadataRetriever, timeUs: Long, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 27) {
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) {
                val s = maxSide.toFloat() / max(w, h)
                if (s < 1f) return r.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1)
                )
            }
        }
        return r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    private fun View.roundCorners(radiusPx: Int) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
            }
        }
        clipToOutline = true
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            hint("No app found for this")
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val KEY_NONE = "none"
        private const val KEY_BLUR = "blur"
        private const val KEY_ADD = "add"
        private const val DRIVE_PACKAGE = "com.google.android.apps.docs"
        /** ISO x seconds above which the room counts as too dark (e.g. ISO 1600 at 1/40 s). */
        private const val DARK_EXPOSURE = 40f
    }
}
