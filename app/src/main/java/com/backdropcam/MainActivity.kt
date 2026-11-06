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
import androidx.core.content.FileProvider
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import com.backdropcam.databinding.ActivityMainBinding
import com.backdropcam.databinding.DialogScriptBinding
import com.backdropcam.databinding.DialogTrimBinding
import com.backdropcam.databinding.SheetRemoteBinding
import com.backdropcam.databinding.SheetMicBinding
import com.backdropcam.databinding.SheetResultBinding
import com.backdropcam.databinding.SheetScriptsBinding
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
 *  - [KeywordSpotter], [Speaker], [FramingCoach]: hands-free mode
 * This class wires the UI to them.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var engine: CompositeEngine
    private lateinit var backdrops: BackdropStore
    private lateinit var scripts: ScriptStore
    private lateinit var speaker: Speaker
    private lateinit var coach: FramingCoach
    private lateinit var voice: KeywordSpotter

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
    private var logoOpen = false
    private var logoTwistAngle: Float? = null
    private var lastFraming: CompositeEngine.Framing? = null
    private var backdropMean: FloatArray? = null

    // Teleprompter
    private var teleLastMs = 0L
    private var teleAccum = 0f
    private var refreshScriptList: (() -> Unit)? = null
    private var teleRunning = false
        set(value) {
            field = value
            main.removeCallbacks(teleTick)
            if (value) {
                teleLastMs = 0L
                main.post(teleTick)
            }
        }

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
                .setMessage("BackdropCam records with the camera, so it cannot work without it. Allow Camera (and Microphone for sound) in the app's settings.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivitySafe(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
        }

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addLogo(uri) }

    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addBackdrop(uri) }

    private val overlay = OverlayComposer()
    private val overlayExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var overlayState = OverlayComposer.State()
    private var overlayDirty = false
    private var introAlpha = 0f
    private var introStartMs = 0L
    private lateinit var brandKit: BrandKit

    private val importKit =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) io.execute {
                try {
                    val what = brandKit.import(uri)
                    val logo = backdrops.loadLogo()
                    main.post {
                        if (logo != null) engine.setLogo(logo)
                        buildBackdropStrip()
                        applyBackdropSelection()
                        updateOverlay()
                        hint("Brand kit imported: $what")
                    }
                } catch (e: Exception) {
                    main.post { hint("Could not import: ${e.message}") }
                }
            }
        }

    private val musicFile by lazy { File(filesDir, "music") }
    private var refreshMusicUi: (() -> Unit)? = null

    private val pickMusic =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) io.execute {
                try {
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size > 80L * 1024 * 1024) error("file is over 80 MB")
                    var name = "Music"
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    contentResolver.openInputStream(uri)!!.use { input -> musicFile.outputStream().use { input.copyTo(it) } }
                    main.post {
                        settings.musicName = name
                        settings.musicOn = true
                        refreshMusicUi?.invoke()
                        hint("Music added: $name")
                    }
                } catch (e: Exception) {
                    main.post { hint("Could not use that music: ${e.message}") }
                }
            }
        }

    private val importScript =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) io.execute {
                try {
                    val (title, body) = scripts.import(uri)
                    main.post { editScript(null, title, body) }
                } catch (e: Exception) {
                    main.post { hint("Could not read that file") }
                }
            }
        }

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
        val crashedLastTime = settings.sessionOpen
        if (crashedLastTime) settings.enterSafeMode()
        backdrops = BackdropStore(this)
        scripts = ScriptStore(this)
        brandKit = BrandKit(this, settings)
        micGain = settings.micGain
        lastVideo = settings.lastVideo?.let(Uri::parse)

        engine = CompositeEngine(this).apply {
            onGeometry = { w, h -> onCameraGeometry(w, h) }
            onFraming = { f ->
                lastFraming = f
                warnIfHeadCut(f)
                if (settings.handsFree && recorder == null && countdown == null && !speaker.isSpeaking) coach.update(f)
            }
            framingWanted = settings.handsFree
            onTooSlow = { fastPreview = true; easeForSlowPhone() }
            fastPreview = settings.lowEndPhone || settings.safeMode
        }
        speaker = Speaker(this)
        coach = FramingCoach { speak(it) }
        voice = KeywordSpotter(this) { word, atUs -> main.post { onKeyword(word, atUs) } }
        // Whenever the phone stops talking (for any reason) the ear opens again shortly after.
        speaker.onIdle = {
            voice.mutedUntil = System.currentTimeMillis() + 700
            startListening()
        }
        if (needsSpeechModel()) loadVoice()
        applyEngineOptions()
        loadSegmenter()

        setupWindow()
        setupPreviewSurface()
        setupGestures()
        setupButtons()
        setupAdjustPanel()
        setupLogoPanel()

        updateMicUi()
        updateTimerUi()
        updateHandsFreeUi()
        updateTeleUi()
        ui.aspectChip.text = viewModeLabel()
        buildBackdropStrip()
        applyBackdropSelection()
        lastVideo?.let { loadThumbInto(it, ui.lastThumb, forgetIfMissing = true) }
        handleShareIntent(intent)

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

    /** After a crash: the app restarts in lighter settings; offer to share the error report. */
    private fun showCrashNotice() {
        val report = CrashGuard.lastReport(this)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("BackdropCam closed unexpectedly")
            .setMessage(
                "It has restarted with lighter settings for this phone: 720p, Fast cut-out, and the " +
                    "cut-out running on the processor instead of the graphics chip. You can raise them again in Settings." +
                    if (report != null) "\n\nSending the error report helps fix the cause." else ""
            )
            .setPositiveButton("OK", null)
        if (report != null) b.setNeutralButton("Send report") { _, _ ->
            startActivitySafe(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "BackdropCam error report")
                        .putExtra(Intent.EXTRA_TEXT, report),
                    "Send report"
                )
            )
        }
        b.show()
        CrashGuard.clear(this)
    }

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
        if (settings.handsFree) speak("It's a bit dark. Try facing a window or a lamp.")
    }

    /** Long 4K recordings make phones hot; say so before the phone itself slows down. */
    private fun watchTemperature() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        var warned = false
        runCatching {
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(this)) { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE && !warned) {
                    warned = true
                    hint("The phone is getting hot. Let it cool down, or record in 1080p to ease the load.")
                } else if (status <= PowerManager.THERMAL_STATUS_LIGHT) {
                    warned = false
                }
            }
        }
    }

    /** Free space on the storage where videos are saved, in MB (-1 if unknown). */
    private fun freeSpaceMb(): Long = runCatching {
        val dir = getExternalFilesDir(null) ?: filesDir
        StatFs(dir.path).availableBytes / (1024 * 1024)
    }.getOrDefault(-1L)

    override fun onResume() {
        super.onResume()
        settings.sessionOpen = true
        foreground = true
        engine.setBackdropVideoPlaying(true)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_UI) }
        }
        startListening()
    }

    override fun onPause() {
        super.onPause()
        settings.sessionOpen = false
        foreground = false
        engine.setBackdropVideoPlaying(false)
        orientationListener.disable()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.unregisterListener(tiltListener)
        cancelCountdown(announce = false)
        if (recorder != null) stopRecording()
        micMonitor?.stop()
        micMonitor = null
        voice.stop()
        speaker.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        teleRunning = false
        remote?.stop()
        voice.close()
        speaker.shutdown()
        engine.release()
        // The executors are left running: a save or upload that is still in flight finishes on
        // its own, and its result is dropped because the activity is gone.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
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
        ui.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                engine.setPreviewSurface(holder.surface, w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = engine.clearPreviewSurface()
        })
    }

    private fun setupButtons() {
        ui.settingsBtn.setOnClickListener { showSettings() }
        ui.flashBtn.setOnClickListener { toggleTorch() }
        ui.aspectChip.setOnClickListener { cycleViewMode() }
        ui.qualityChip.setOnClickListener { cycleQuality() }
        ui.timerChip.setOnClickListener { cycleTimer() }
        ui.micBtn.setOnClickListener { onMicPressed() }
        ui.handsBtn.setOnClickListener { if (!busy()) setHandsFree(!settings.handsFree) }
        ui.tuneBtn.setOnClickListener { setAdjustOpen(!adjustOpen) }
        ui.logoBtn.setOnClickListener { onLogoButton() }
        ui.remoteBtn.setOnClickListener { showRemoteSheet() }
        ui.teleBtn.setOnClickListener { showScriptsSheet() }
        // Tap the script to pause / resume scrolling (also handy for rehearsing).
        val toggleTele = View.OnClickListener { teleRunning = !teleRunning }
        ui.teleBox.setOnClickListener(toggleTele)
        ui.teleText.setOnClickListener(toggleTele)
        ui.recordBtn.setOnClickListener { onRecordPressed() }
        ui.pauseBtn.setOnClickListener { togglePause() }
        ui.flipBtn.setOnClickListener { flipCamera() }
        ui.lastThumb.setOnClickListener { lastVideo?.let { showResultSheet(it) } ?: hint("No recordings yet") }
        ui.lastThumb.setOnLongClickListener {
            // All recordings live in the gallery under Movies/BackdropCam.
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
                    updateTeleUi()
                    ui.aspectChip.text = viewModeLabel()
                    updateOverlay()
                }
            }
        }
    }

    // ================================================================== camera

    private fun bindCamera() {
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
            updateTeleUi()
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
        ui.zoomRow.visibility = if (ui.zoomRow.childCount > 0 && !adjustOpen && !logoOpen) View.VISIBLE else View.GONE
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
        updateOverlay()
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
            else -> "max"
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
                if (logoOpen) {
                    updateLogo { it.copy(size = (it.size * d.scaleFactor).coerceIn(0.05f, 0.9f)) }
                    return true
                }
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
                    logoOpen -> {
                        val (wx, wy) = screenToWorld(dx, dy)
                        updateLogo { it.copy(x = (it.x + wx).coerceIn(0f, 1f), y = (it.y + wy).coerceIn(0f, 1f)) }
                    }
                    adjustOpen -> movePerson(dx, dy)
                    else -> return false
                }
                return true
            }
        })
        ui.surface.setOnTouchListener { _, e ->
            if (logoOpen) twistLogo(e)
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

    private fun loadSegmenter() {
        val hq = settings.highQualityCutout
        io.execute {
            try {
                engine.setSegmenter(PersonSegmenter(this, hq, allowGpu = !settings.safeMode))
            } catch (e: Exception) {
                main.post { hint("Could not load the cut-out model") }
            }
        }
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
        applyLogo()
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
                val bmp = backdrops.load(key)
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

    private enum class AdjustTab { SIZE, LIGHT, WARMTH }

    private var adjustTab = AdjustTab.SIZE

    /**
     * One slider, three icon tabs (size / brightness / warmth). Keeps the panel short so it never
     * covers you while you watch the effect.
     */
    private fun setupAdjustPanel() {
        ui.adjustSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            when (adjustTab) {
                AdjustTab.SIZE -> settings.personScale = v / 100f
                AdjustTab.LIGHT -> settings.brightness = v / 100f
                AdjustTab.WARMTH -> settings.warmth = v / 100f
            }
            updateAdjustValue()
            applyPersonAndColor()
        }
        ui.tabSize.setOnClickListener { selectAdjustTab(AdjustTab.SIZE, explain = true) }
        ui.tabLight.setOnClickListener { selectAdjustTab(AdjustTab.LIGHT, explain = true) }
        ui.tabWarmth.setOnClickListener { selectAdjustTab(AdjustTab.WARMTH, explain = true) }
        ui.autoMatchBtn.setOnClickListener { autoMatchLight() }
        ui.resetAdjustBtn.setOnClickListener {
            settings.personScale = 1f; settings.personOffX = 0f; settings.personOffY = 0f
            settings.brightness = 0f; settings.warmth = 0f
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
            s.value = ((if (tab == AdjustTab.LIGHT) settings.brightness else settings.warmth) * 100f).coerceIn(-100f, 100f)
        }
        setActive(ui.tabSize, tab == AdjustTab.SIZE)
        setActive(ui.tabLight, tab == AdjustTab.LIGHT)
        setActive(ui.tabWarmth, tab == AdjustTab.WARMTH)
        updateAdjustValue()
        if (explain) hint(
            when (tab) {
                AdjustTab.SIZE -> "Size: make yourself smaller over the backdrop. Drag the picture to move."
                AdjustTab.LIGHT -> "Brightness of you (not the backdrop)"
                AdjustTab.WARMTH -> "Warmth of you: left cooler, right warmer"
            }
        )
    }

    private fun updateAdjustValue() {
        ui.adjustValue.text = when (adjustTab) {
            AdjustTab.SIZE -> "${(settings.personScale * 100f).roundToInt()}%"
            AdjustTab.LIGHT -> signed(settings.brightness)
            AdjustTab.WARMTH -> signed(settings.warmth)
        }
    }

    private fun signed(v: Float): String {
        val n = (v * 100f).roundToInt()
        return if (n > 0) "+$n" else "$n"
    }

    private fun applyPersonAndColor() {
        engine.setPersonTransform(settings.personScale, settings.personOffX, settings.personOffY)
        engine.setColorAdjust(2f.pow(settings.brightness), settings.warmth)
    }

    /** The adjust panel replaces the lens switch and backdrop strip while open, to save space. */
    private fun setAdjustOpen(open: Boolean) {
        if (open && logoOpen) setLogoOpen(false)
        adjustOpen = open
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

    /** Sets brightness and warmth so you match the backdrop's light. */
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
        val tp = (f.meanR - f.meanB) / (lp + 0.05f)
        val tb = (bg[0] - bg[2]) / (lb + 0.05f)
        settings.brightness = (ln(gainValue) / ln(2f)).coerceIn(-1f, 1f)
        settings.warmth = ((tb - tp) * 3f).coerceIn(-1f, 1f)
        selectAdjustTab(if (adjustTab == AdjustTab.SIZE) AdjustTab.LIGHT else adjustTab, explain = false)
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
            settings.handsFree -> startCountdown(max(settings.timer, 3))
            settings.timer > 0 -> startCountdown(settings.timer)
            else -> startRecording()
        }
    }

    private fun startCountdown(seconds: Int) {
        val spoken = settings.handsFree
        val tone = if (spoken) null else runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
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
                if (spoken) speak(n) else tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
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

    private fun cancelCountdown(announce: Boolean = true) {
        if (countdown == null) return
        countdown?.cancel()
        countdown = null
        countdownTone?.release()
        countdownTone = null
        ui.countdownText.visibility = View.GONE
        if (recorder == null) setShutter(false)
        if (announce && settings.handsFree) speak("Cancelled.")
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
        if (settings.micOn && !audio) hint("Recording without sound: microphone access is not allowed for BackdropCam")
        val listenForStop = settings.handsFree && audio
        if (settings.handsFree && !audio) hint("Without sound, say nothing: tap the button or press a volume key to stop")

        micMonitor?.stop()
        micMonitor = null
        voice.stop() // free the microphone for the recorder
        voice.reset()
        voice.mutedUntil = 0L
        speaker.stop()
        micMuted = false
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
                onPcm = if (listenForStop) { pcm: ShortArray, n: Int, t: Long -> voice.feed48k(pcm, n, t) } else null,
                music = if (audio && settings.musicOn && musicFile.exists()) MusicSource(musicFile) else null,
                musicVolume = { settings.musicVolume },
                musicDuck = { settings.musicDuck },
                preferHevc = settings.smallFiles,
            )
            rec.start()
            engine.startRecording(rec.inputSurface, size.width, size.height, rotation, { rec.ptsNs() }, { rec.paused })
            recorder = rec
        } catch (e: Exception) {
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
        if (ui.teleBox.visibility == View.VISIBLE) {
            ui.teleScroll.scrollTo(0, 0)
            teleRunning = true
        }
        setControlsEnabled(false)
        updateMicUi()
        if (settings.introOn && (settings.introTitle.isNotBlank() || settings.brandName.isNotBlank())) {
            introStartMs = System.currentTimeMillis()
            tickIntro()
        }
        tickTimer()
    }

    private fun tickTimer() {
        val rec = recorder ?: return
        updateRecTime(rec)
        val limit = settings.autoStop
        if (limit > 0 && rec.ptsNs() / 1_000_000_000L >= limit) {
            stopRecording()
            return
        }
        main.postDelayed({ tickTimer() }, 250)
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
            voice.reset() // word timings restart with the resumed recording clock
            recDotBlink?.resume()
            if (ui.teleBox.visibility == View.VISIBLE) teleRunning = true
            ui.pauseBtn.setImageResource(R.drawable.ic_pause)
        } else {
            rec.pause()
            recDotBlink?.pause()
            teleRunning = false
            ui.pauseBtn.setImageResource(R.drawable.ic_play_arrow)
        }
        updateRecTime(rec)
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        if (introAlpha > 0f) {
            introAlpha = 0f
            updateOverlay()
        }
        setShutter(false)
        recDotBlink?.cancel()
        recDotBlink = null
        ui.recPill.visibility = View.GONE
        ui.quickRow.visibility = View.VISIBLE
        ui.pauseBtn.visibility = View.GONE
        ui.flipBtn.visibility = View.VISIBLE
        teleRunning = false
        setControlsEnabled(true)
        updateMicUi()
        engine.stopRecording {
            io.execute {
                try {
                    val uri = rec.finish()
                    main.post { onVideoSaved(uri, rec) }
                } catch (e: Exception) {
                    main.post {
                        hint("Could not save the video: ${e.message}")
                        if (settings.handsFree) speak("Sorry, saving failed.")
                    }
                }
            }
        }
    }

    private fun onVideoSaved(uri: Uri, rec: VideoRecorder) {
        lastVideo = uri
        lastLink = null
        uploadMessage = null
        uploadProgress = 0
        settings.lastVideo = uri.toString()
        loadThumbInto(uri, ui.lastThumb)
        if (isFinishing || isDestroyed) return
        showResultSheet(uri, "${rec.width} × ${rec.height} · Movies/BackdropCam")
        if (settings.autoUpload && settings.driveInApp) startUpload()
        if (settings.handsFree) speak("Saved. Say start to record again.") { coach.reset() }
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
        voice.stop() // the level test needs the microphone
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            micMonitor = MicMonitor({ micGain }) { level ->
                main.post { b.levelMeter.setProgressCompat((level * 100).toInt(), true) }
            }.also { it.start() }
        }
        dlg.setOnDismissListener {
            micMonitor?.stop()
            micMonitor = null
            startListening()
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

        // Music
        val refreshMusic = {
            val has = musicFile.exists()
            b.musicName.text = if (has) settings.musicName.ifBlank { "Music" } else "No music chosen"
            b.musicRemoveBtn.isEnabled = has
            b.musicSwitch.isEnabled = has
            b.musicSwitch.isChecked = has && settings.musicOn
        }
        refreshMusicUi = refreshMusic
        dlg.setOnDismissListener { if (refreshMusicUi === refreshMusic) refreshMusicUi = null }
        refreshMusic()
        b.musicPickBtn.setOnClickListener { pickMusic.launch(arrayOf("audio/*")) }
        b.musicRemoveBtn.setOnClickListener {
            musicFile.delete()
            settings.musicOn = false
            refreshMusic()
        }
        b.musicSwitch.setOnCheckedChangeListener { _, on ->
            settings.musicOn = on
            if (on && !settings.micOn) hint("Music needs \"Record sound\" on (mic button)")
        }
        b.musicVolume.value = (settings.musicVolume * 100f).coerceIn(0f, 100f)
        b.musicVolume.addOnChangeListener { _, v, _ -> settings.musicVolume = v / 100f }
        b.musicDuckSwitch.isChecked = settings.musicDuck
        b.musicDuckSwitch.setOnCheckedChangeListener { _, on -> settings.musicDuck = on }

        // Brand
        b.brandName.setText(settings.brandName)
        b.brandRole.setText(settings.brandRole)
        b.introTitle.setText(settings.introTitle)
        b.brandName.doAfterTextChanged { settings.brandName = it.toString().trim(); updateOverlay() }
        b.brandRole.doAfterTextChanged { settings.brandRole = it.toString().trim(); updateOverlay() }
        b.introTitle.doAfterTextChanged { settings.introTitle = it.toString().trim() }
        b.nameTitleSwitch.isChecked = settings.nameTitleOn
        b.nameTitleSwitch.setOnCheckedChangeListener { _, on ->
            settings.nameTitleOn = on
            if (on && settings.brandName.isBlank()) hint("Type your name above to show it")
            updateOverlay()
        }
        b.introSwitch.isChecked = settings.introOn
        b.introSwitch.setOnCheckedChangeListener { _, on ->
            settings.introOn = on
            if (on && settings.introTitle.isBlank() && settings.brandName.isBlank()) hint("Type an intro title or your name, or the card has nothing to show")
        }
        fun drawColors() {
            b.brandColors.removeAllViews()
            for (c in OverlayComposer.BRAND_COLORS) {
                b.brandColors.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(if (c == settings.brandColor) 3 else 1), if (c == settings.brandColor) 0xFFFFFFFF.toInt() else 0x55FFFFFF)
                    }
                    contentDescription = "Brand colour"
                    setOnClickListener {
                        settings.brandColor = c
                        drawColors()
                        updateOverlay()
                    }
                })
            }
        }
        drawColors()
        b.exportKitBtn.setOnClickListener { shareBrandKit() }
        b.importKitBtn.setOnClickListener { importKit.launch(arrayOf("application/zip", "application/octet-stream")) }
        dlg.show()
    }

    // ================================================================== result sheet and Google Drive

    private fun showResultSheet(uri: Uri, details: String? = null) {
        val b = SheetResultBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        resultUi = b
        b.details.text = details ?: "Movies/BackdropCam"
        b.thumb.roundCorners(dp(16))
        loadThumbInto(uri, b.thumb)
        b.playBtn.setOnClickListener { playVideo(uri) }
        b.shareVideoBtn.setOnClickListener {
            startActivitySafe(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share video"
                )
            )
        }
        b.uploadBtn.setOnClickListener {
            if (settings.driveInApp) startUpload() else saveWithDriveApp(uri)
        }
        b.trimBtn.setOnClickListener { showTrimDialog(uri, dlg) }
        b.retakeBtn.setOnClickListener {
            dlg.dismiss()
            deleteVideo(uri)
            hint("Deleted. Recording again")
            onRecordPressed()
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
        video.setOnPreparedListener { controls.show(3000); video.start() }
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
            pauseCameraForPlayback(false)
        }
        pauseCameraForPlayback(true)
        dlg.show()
    }

    private var playbackPauses = 0

    /**
     * While a recording is played back (or previewed for trimming) the camera, the cut-out and
     * the voice listener are switched off: they would waste battery and memory, and hands-free
     * could even hear "start" in the video's own sound.
     */
    private fun pauseCameraForPlayback(pause: Boolean) {
        if (pause) {
            if (playbackPauses++ > 0) return
            voice.stop()
            speaker.stop()
            engine.setBackdropVideoPlaying(false)
            runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
            camera = null
        } else {
            if (playbackPauses == 0 || --playbackPauses > 0) return
            engine.setBackdropVideoPlaying(true)
            bindCamera()
            startListening()
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
            !settings.driveInApp -> "Save to Google Drive"
            uploading -> "Uploading…"
            lastLink != null -> "Uploaded to Google Drive"
            else -> "Upload to Google Drive and get link"
        }
        b.uploadProgress.visibility = if (uploading) View.VISIBLE else View.GONE
        b.uploadProgress.setProgressCompat(uploadProgress, true)
        val msg = lastLink ?: uploadMessage
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
        // Not set up with Google: go straight to the Drive app (no extra question), and use it
        // from now on. In-app upload can be switched back on in Settings once it is set up.
        settings.driveInApp = false
        uploadFinished(null, null)
        lastVideo?.let { saveWithDriveApp(it) }
    }

    /** Hands the video to the Google Drive app ("Save to Drive"); no setup needed. */
    private fun saveWithDriveApp(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val drive = Intent(send).setPackage("com.google.android.apps.docs")
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
                val link = DriveUploader(contentResolver, token).uploadAndShare(uri) { pct ->
                    main.post {
                        uploadProgress = pct
                        refreshResultUi()
                    }
                }
                main.post { uploadFinished(link, null) }
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
            if (resultUi == null) hint("Uploaded to Google Drive. Link copied.")
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

    // ================================================================== hands-free

    private fun setHandsFree(on: Boolean) {
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) return hint("Hands-free needs microphone permission")
        settings.handsFree = on
        engine.framingWanted = on
        updateHandsFreeUi()
        coach.reset()
        if (on) {
            if (!settings.micOn) hint("Hands-free on. Turn Record sound on (mic button) so it can hear “stop” while recording")
            else hint("Hands-free on: say “start” to record, “stop” to finish")
            speak("Hands-free on. I will guide you into the frame. Say start to record, and say stop to finish.")
            loadVoice()
        } else {
            speaker.stop()
            voice.stop()
            if (!needsSpeechModel()) voice.unload()
            hint("Hands-free off")
        }
    }

    /** The offline speech model (about 100 MB of memory) is only loaded for hands-free mode. */
    private fun needsSpeechModel() = settings.handsFree

    /** Loads the offline speech model (about 100 MB of memory), so only while hands-free is on. */
    private fun loadVoice() {
        if (voice.available) return
        if (!File(filesDir, "vosk-en").exists()) hint("Preparing speech recognition, this takes a moment the first time")
        voice.load { ok ->
            main.post {
                when {
                    !needsSpeechModel() -> voice.unload() // switched off again while loading
                    !ok -> hint("Voice commands could not start. Guidance still works.")
                    else -> startListening()
                }
            }
        }
    }

    private fun updateHandsFreeUi() = setActive(ui.handsBtn, settings.handsFree)

    /**
     * Says [text] out loud. Keyword listening ignores what is heard while the phone speaks, so it
     * can never react to its own "say start"; [Speaker.onIdle] lifts the mute afterwards.
     */
    private fun speak(text: String, done: (() -> Unit)? = null) {
        voice.mutedUntil = Long.MAX_VALUE
        speaker.say(text, done)
    }

    private fun startListening() {
        if (foreground && settings.handsFree && recorder == null && micMonitor == null && playbackPauses == 0) voice.start()
    }

    /** "start"/"action" while idle; "stop"/"cut" while recording or counting down. */
    private fun onKeyword(word: String, atUs: Long) {
        if (!settings.handsFree) return
        if (word in KeywordSpotter.START_WORDS) {
            if (recorder == null && countdown == null) {
                speak("Okay. Recording in") {
                    if (recorder == null && countdown == null) startCountdown(max(settings.timer, 3))
                }
            }
        } else if (word in KeywordSpotter.STOP_WORDS) {
            val rec = recorder
            when {
                rec != null -> {
                    rec.cutoffUs = max(0L, atUs - 250_000L) // cut the spoken word off the end
                    stopRecording()
                }
                countdown != null -> cancelCountdown()
            }
        }
    }

    // ================================================================== teleprompter

    private val teleTick = object : Runnable {
        override fun run() {
            if (!teleRunning || ui.teleBox.visibility != View.VISIBLE) return
            val now = System.currentTimeMillis()
            val dt = if (teleLastMs == 0L) 0f else (now - teleLastMs) / 1000f
            teleLastMs = now
            teleAccum += settings.teleSpeed * dp(9) * dt
            val step = teleAccum.toInt()
            if (step > 0) {
                ui.teleScroll.scrollBy(0, step)
                teleAccum -= step
            }
            main.postDelayed(this, 16)
        }
    }

    private fun updateTeleUi() {
        val front = settings.frontCamera
        ui.teleBtn.visibility = if (front) View.VISIBLE else View.GONE
        val script = settings.teleScript?.let { scripts.load(it) }
        // The script box is laid out for portrait; it would read sideways with the phone turned.
        val show = front && settings.teleOn && script != null && script.body.isNotBlank() && deviceRotation % 180 == 0
        ui.teleBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) teleRunning = false
        if (script != null) ui.teleText.text = script.body
        ui.teleText.textSize = settings.teleSize.toFloat()
        setActive(ui.teleBtn, show)
    }

    private fun showScriptsSheet() {
        val b = SheetScriptsBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        b.teleSwitch.isChecked = settings.teleOn
        b.teleSwitch.setOnCheckedChangeListener { _, on -> settings.teleOn = on; updateTeleUi() }
        b.speedSlider.value = settings.teleSpeed.coerceIn(1, 10).toFloat()
        b.speedSlider.addOnChangeListener { _, v, _ -> settings.teleSpeed = v.toInt() }
        b.sizeSlider.value = (settings.teleSize.coerceIn(18, 48) / 2 * 2).toFloat()
        b.sizeSlider.addOnChangeListener { _, v, _ -> settings.teleSize = v.toInt(); updateTeleUi() }
        b.newScriptBtn.setOnClickListener { editScript(null, "", "") }
        b.importScriptBtn.setOnClickListener {
            if (!busy()) importScript.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        }
        val refresh = {
            b.scriptList.removeAllViews()
            val list = scripts.list()
            if (list.isEmpty()) {
                b.scriptList.addView(TextView(this).apply {
                    text = "No scripts yet. Tap New script or Import."
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            for (sc in list) {
                val active = sc.id == settings.teleScript
                b.scriptList.addView(TextView(this).apply {
                    text = (if (active) "✓  " else "") + sc.title
                    textSize = 16f
                    setTextColor(if (active) ContextCompat.getColor(context, R.color.accent) else 0xFFFFFFFF.toInt())
                    setPadding(0, dp(12), 0, dp(12))
                    setOnClickListener {
                        settings.teleScript = sc.id
                        settings.teleOn = true
                        b.teleSwitch.isChecked = true
                        updateTeleUi()
                        refreshScriptList?.invoke()
                    }
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(sc.title)
                            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                                if (which == 0) editScript(sc.id, sc.title, sc.body)
                                else {
                                    scripts.delete(sc.id)
                                    if (settings.teleScript == sc.id) settings.teleScript = null
                                    updateTeleUi()
                                    refreshScriptList?.invoke()
                                }
                            }
                            .show()
                        true
                    }
                })
            }
        }
        refreshScriptList = refresh
        refresh()
        dlg.setOnDismissListener { if (refreshScriptList === refresh) refreshScriptList = null }
        dlg.show()
    }

    /** Opens the script editor; saving makes it the active teleprompter script. */
    private fun editScript(id: String?, title: String, body: String) {
        val b = DialogScriptBinding.inflate(layoutInflater)
        b.scriptTitle.setText(title)
        b.scriptBody.setText(body)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (id == null) "New script" else "Edit script")
            .setView(b.root)
            .setPositiveButton("Save") { _, _ ->
                settings.teleScript = scripts.save(id, b.scriptTitle.text.toString(), b.scriptBody.text.toString())
                settings.teleOn = true
                updateTeleUi()
                refreshScriptList?.invoke()
                if (!settings.frontCamera) hint("Script saved. Switch to the front camera to use it.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Text shared from Google Docs, Notes, WhatsApp… becomes a new teleprompter script. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared script"
        intent.action = null
        editScript(null, title, text)
    }

    // ================================================================== logo

    private fun setupLogoPanel() {
        ui.logoOpacity.addOnChangeListener { _, v, fromUser ->
            if (fromUser) updateLogo { it.copy(opacity = v / 100f) }
        }
        ui.logoPickBtn.setOnClickListener {
            if (!busy()) pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        ui.logoShowBtn.setOnClickListener {
            settings.logoOn = !settings.logoOn
            applyLogo()
            hint(if (settings.logoOn) "Logo shown" else "Logo hidden")
        }
        ui.logoLayerBtn.setOnClickListener {
            updateLogo { it.copy(behind = !it.behind) }
            hint(if (logoPlacement().behind) "Logo behind you, like a sign on the wall" else "Logo in front of you, like a TV logo")
        }
        ui.logoDeleteBtn.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove the logo?")
                .setPositiveButton("Remove") { _, _ ->
                    backdrops.deleteLogo()
                    engine.setLogo(null)
                    setLogoOpen(false)
                    updateLogoButton()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        ui.logoDoneBtn.setOnClickListener { setLogoOpen(false) }
        io.execute {
            val bmp = backdrops.loadLogo()
            if (bmp != null) engine.setLogo(bmp)
        }
        applyLogo()
    }

    private fun onLogoButton() {
        when {
            logoOpen -> setLogoOpen(false)
            !backdrops.hasLogo -> {
                if (!busy()) pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            else -> setLogoOpen(true)
        }
    }

    private fun addLogo(uri: Uri) {
        io.execute {
            try {
                val bmp = backdrops.saveLogo(uri)
                engine.setLogo(bmp)
                main.post {
                    settings.logoOn = true
                    applyLogo()
                    setLogoOpen(true)
                    hint("Drag to move the logo, pinch to resize, twist with two fingers to rotate")
                }
            } catch (e: Exception) {
                main.post { hint("Could not use that image") }
            }
        }
    }

    /** The logo panel replaces the lens switch and backdrops while open; gestures move the logo. */
    private fun setLogoOpen(open: Boolean) {
        if (open && adjustOpen) setAdjustOpen(false)
        logoOpen = open
        ui.logoPanel.visibility = if (open) View.VISIBLE else View.GONE
        ui.stripScroll.visibility = if (open || adjustOpen) View.GONE else View.VISIBLE
        updateLensRowVisibility()
        updateLogoButton()
        if (open) applyLogo()
    }

    private fun logoPlacement() = settings.logoPlacement(settings.backdrop)

    /** Changes this backdrop's logo placement and shows it at once. */
    private fun updateLogo(change: (LogoPlacement) -> LogoPlacement) {
        settings.setLogoPlacement(settings.backdrop, change(logoPlacement()))
        applyLogo()
    }

    private fun applyLogo() {
        val p = logoPlacement()
        engine.setLogoPlacement(settings.logoOn && backdrops.hasLogo, p)
        ui.logoOpacity.value = (p.opacity * 100f).coerceIn(10f, 100f)
        ui.logoValue.text = "${(p.opacity * 100f).roundToInt()}%"
        ui.logoShowBtn.setImageResource(if (settings.logoOn) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        ui.logoLayerBtn.setImageResource(if (p.behind) R.drawable.ic_flip_to_back else R.drawable.ic_flip_to_front)
        updateLogoButton()
    }

    private fun updateLogoButton() = setActive(ui.logoBtn, logoOpen || (settings.logoOn && backdrops.hasLogo))

    /** Two-finger twist rotates the logo. */
    private fun twistLogo(e: MotionEvent) {
        if (e.pointerCount < 2 || e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            logoTwistAngle = null
            return
        }
        val angle = Math.toDegrees(
            kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
        ).toFloat()
        val last = logoTwistAngle
        if (last != null && e.actionMasked == MotionEvent.ACTION_MOVE) {
            var delta = angle - last
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            // Screen angles grow clockwise; the logo's rotation grows counter-clockwise.
            updateLogo { it.copy(rotation = (it.rotation - delta) % 360f) }
        }
        logoTwistAngle = angle
    }

    // ================================================================== second-screen remote

    private var remote: RemoteServer? = null
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
            if (server.busy) return@listener
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            remoteExecutor.execute {
                server.pushFrame(copy)
                copy.recycle()
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

    // ================================================================== text overlay and brand kit

    /** Redraws the text layer (name title, intro card, captions) for the current picture shape. */
    private fun updateOverlay() {
        val nw = engine.naturalWidth
        val nh = engine.naturalHeight
        if (nw == 0 || nh == 0) return
        var vw = nw * cropX
        var vh = nh * cropY
        if (deviceRotation % 180 != 0) vw = vh.also { vh = vw }
        overlayState = OverlayComposer.State(
            name = settings.brandName,
            role = settings.brandRole,
            brandColor = settings.brandColor,
            showNameTitle = settings.nameTitleOn,
            introTitle = settings.introTitle,
            introSubtitle = "",
            introAlpha = introAlpha,
            caption = "",
        )
        // Coalesce: only the newest state is drawn.
        if (overlayDirty) return
        overlayDirty = true
        overlayExecutor.execute {
            main.post { overlayDirty = false }
            val bmp = runCatching { overlay.render(vw, vh, overlayState) }.getOrNull()
            engine.setOverlay(bmp)
        }
    }


    /** Intro card: fades in, holds, fades out over the first ~3 seconds of a recording. */
    private fun tickIntro() {
        if (recorder == null) return
        val t = (System.currentTimeMillis() - introStartMs) / 1000f
        introAlpha = when {
            t < 0.35f -> t / 0.35f
            t < 2.6f -> 1f
            t < 3.1f -> 1f - (t - 2.6f) / 0.5f
            else -> 0f
        }
        updateOverlay()
        if (t < 3.1f) main.postDelayed({ tickIntro() }, 80)
    }

    private fun shareBrandKit() {
        io.execute {
            try {
                val file = brandKit.export()
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                main.post {
                    startActivitySafe(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Share brand kit"
                        )
                    )
                }
            } catch (e: Exception) {
                main.post { hint("Could not create the kit: ${e.message}") }
            }
        }
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
            ui.micBtn, ui.handsBtn, ui.tuneBtn, ui.teleBtn,
            ui.pauseBtn, ui.flipBtn, ui.lastThumb, ui.countdownText, ui.recPill, ui.hintText,
            ui.tabSize, ui.tabLight, ui.tabWarmth, ui.adjustValue, ui.logoBtn, ui.remoteBtn,
            ui.logoPickBtn, ui.logoShowBtn, ui.logoLayerBtn, ui.logoDeleteBtn, ui.logoDoneBtn, ui.logoValue,
            ui.autoMatchBtn, ui.resetAdjustBtn, ui.doneAdjustBtn
        )
        for (i in 0 until ui.zoomRow.childCount) views += ui.zoomRow.getChildAt(i)
        for (i in 0 until ui.backdropStrip.childCount) views += ui.backdropStrip.getChildAt(i)
        for (v in views) v.animate().rotation(angle).setDuration(200).start()
    }

    private fun loadThumbInto(uri: Uri, target: ImageView, forgetIfMissing: Boolean = false) {
        io.execute {
            val bmp: Bitmap? = try {
                MediaMetadataRetriever().run {
                    setDataSource(this@MainActivity, uri)
                    frameAt(this, 0L, 320).also { release() }
                }
            } catch (e: Exception) {
                null
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
        /** ISO x seconds above which the room counts as too dark (e.g. ISO 1600 at 1/40 s). */
        private const val DARK_EXPOSURE = 40f
    }
}
