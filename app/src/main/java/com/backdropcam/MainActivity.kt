package com.backdropcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Outline
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
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
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.hardware.camera2.CameraCharacteristics
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan
import androidx.core.view.updatePadding
import com.backdropcam.databinding.ActivityMainBinding
import com.backdropcam.databinding.SheetMicBinding
import com.backdropcam.databinding.SheetResultBinding
import com.backdropcam.databinding.SheetSettingsBinding
import com.backdropcam.databinding.SheetScriptsBinding
import com.backdropcam.databinding.DialogScriptBinding
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var engine: CompositeEngine
    private lateinit var backdrops: BackdropStore
    private val io = Executors.newSingleThreadExecutor()
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var recorder: VideoRecorder? = null
    private var recordStartMs = 0L
    private var countdown: CountDownTimer? = null
    private var deviceRotation = 0
    private var torchOn = false
    private var pendingWideZoom = false
    private var wideCam: Pair<CameraInfo, Float>? = null
    private var usingWideCam = false
    private var noWideToastShown = false
    private var cropX = 1f
    private var cropY = 1f
    private var boundWide43 = false
    private var adjustOpen = false
    private var lastFraming: CompositeEngine.Framing? = null
    private var backdropMean: FloatArray? = null

    // Teleprompter
    private lateinit var scripts: ScriptStore
    private var teleRunning = false
    private var teleAccum = 0f
    private var teleLastMs = 0L
    private var refreshScriptList: (() -> Unit)? = null

    // Hands-free mode
    private lateinit var speaker: Speaker
    private lateinit var coach: FramingCoach
    private lateinit var voice: VoiceCommands

    @Volatile private var micGain = 1f
    @Volatile private var micMuted = false
    private var micMonitor: MicMonitor? = null

    // Upload / result state
    private var lastVideo: Uri? = null
    private var lastLink: String? = null
    private var uploading = false
    private var uploadProgress = 0
    private var uploadMessage: String? = null
    private var resultUi: SheetResultBinding? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) bindCamera()
            else toast("BackdropCam needs the camera to work")
        }

    private val importScript =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) io.execute {
                try {
                    val (title, body) = scripts.import(uri)
                    main.post { editScript(null, title, body) }
                } catch (e: Exception) {
                    main.post { toast("Could not read that file: ${e.message}") }
                }
            }
        }

    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addBackdrop(uri) }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val token = try {
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken
            } catch (e: Exception) {
                null
            }
            if (token != null) upload(token) else uploadFinished(null, "Upload cancelled")
        }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        settings = AppSettings(this)
        backdrops = BackdropStore(this)
        micGain = settings.micGain
        lastVideo = settings.lastVideo?.let(Uri::parse)

        engine = CompositeEngine(this)
        engine.onGeometry = { w, h -> onCameraGeometry(w, h) }
        speaker = Speaker(this)
        coach = FramingCoach { speak(it) }
        voice = VoiceCommands(this) { onVoiceStart() }
        engine.framingWanted = settings.handsFree
        engine.onFraming = { f ->
            lastFraming = f
            if (settings.handsFree && recorder == null && countdown == null && !speaker.isSpeaking) coach.update(f)
        }
        applyEngineOptions()
        loadSegmenter()

        // Full-screen camera: hide the status and navigation bars (swipe from an edge to show them).
        WindowInsetsControllerCompat(window, ui.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ui.root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) applyCrop()
        }
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ui.topBar.updatePadding(top = bars.top + dp(8))
            ui.bottomArea.updatePadding(bottom = bars.bottom + dp(8))
            insets
        }

        ui.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                engine.setPreviewSurface(holder.surface, w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine.clearPreviewSurface()
            }
        })
        setupGestures()

        ui.settingsBtn.setOnClickListener { showSettings() }
        ui.micBtn.setOnClickListener { onMicPressed() }
        ui.flashBtn.setOnClickListener { toggleTorch() }
        ui.handsBtn.setOnClickListener { setHandsFree(!settings.handsFree, announce = true) }
        ui.timerBtn.setOnClickListener { cycleTimer() }
        ui.qualityChip.setOnClickListener { cycleQuality() }
        ui.aspectChip.setOnClickListener { cycleViewMode() }
        ui.tuneBtn.setOnClickListener { setAdjustOpen(!adjustOpen) }
        ui.teleBtn.setOnClickListener { showScriptsSheet() }
        ui.teleBox.setOnClickListener { teleRunning = !teleRunning }
        ui.teleText.setOnClickListener { teleRunning = !teleRunning }
        setupAdjustPanel()
        ui.pauseBtn.setOnClickListener { togglePause() }
        ui.flipBtn.setOnClickListener { flipCamera() }
        ui.recordBtn.setOnClickListener { onRecordPressed() }
        ui.lastThumb.setOnClickListener { lastVideo?.let { showResultSheet(it) } ?: toast("No recordings yet") }

        updateMicIcon()
        updateTimerUi()
        updateHandsFreeUi()
        ui.aspectChip.text = viewModeLabel()
        scripts = ScriptStore(this)
        updateTeleUi()
        main.post(teleTick)
        handleShareIntent(intent)
        buildBackdropStrip()
        applyBackdropSelection()
        lastVideo?.let { loadThumbInto(it, ui.lastThumb) }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.all { granted(it) }) bindCamera() else permissionLauncher.launch(needed.toTypedArray())
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val target = when {
                    o in 60..120 -> 90
                    o in 150..210 -> 180
                    o in 240..300 -> 270
                    o <= 30 || o >= 330 -> 0
                    else -> return // dead zone avoids flicker between orientations
                }
                if (target != deviceRotation && recorder == null) {
                    deviceRotation = target
                    engine.setDeviceRotation(target)
                    rotateControls(target)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (settings.handsFree && recorder == null) startListening()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        cancelCountdown()
        if (recorder != null) stopRecording()
        micMonitor?.stop()
        micMonitor = null
        voice.stop()
        speaker.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(teleTick)
        voice.stop()
        speaker.shutdown()
        engine.release()
        io.shutdown()
        uploadExecutor.shutdown()
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

    // ================================================================== camera

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            val front = settings.frontCamera
            wideCam = if (front) null else runCatching { findUltraWide(provider.availableCameraInfos) }.getOrNull()
            val wide = wideCam
            usingWideCam = !front && settings.wideStart && wide != null
            val selector = when {
                front -> CameraSelector.DEFAULT_FRONT_CAMERA
                usingWideCam && wide != null -> {
                    val id = cameraId(wide.first)
                    CameraSelector.Builder().addCameraFilter { list -> list.filter { cameraId(it) == id } }.build()
                }
                else -> CameraSelector.DEFAULT_BACK_CAMERA
            }
            val info = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
            if (info == null) {
                if (front) {
                    settings.frontCamera = false
                    bindCamera()
                } else toast("No camera found")
                return@addListener
            }
            val p = Preview.Builder()
                .setResolutionSelector(highestResolutionSelector(settings.qualityCap, settings.viewMode == "43"))
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            p.setSurfaceProvider(engine.surfaceProvider(front, info.sensorRotationDegrees))
            try {
                camera = provider.bindToLifecycle(this, selector, p)
                preview = p
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
                return@addListener
            }
            torchOn = false
            boundWide43 = settings.viewMode == "43"
            setupCameraControls()
            updateTeleUi()
        }, ContextCompat.getMainExecutor(this))
    }

    /** The largest 16:9 camera mode (up to [cap] on the long side) the video encoder can also record. */
    private fun highestResolutionSelector(cap: Int, fourByThree: Boolean): ResolutionSelector {
        val ratio = if (fourByThree) 4f / 3f else 16f / 9f
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (fourByThree) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .setResolutionFilter { sizes, _ ->
                val good = sizes
                    .filter { max(it.width, it.height) <= cap }
                    .filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f || abs(it.height.toFloat() / it.width - ratio) < 0.02f }
                    .filter { VideoRecorder.isSizeSupported(it.width, it.height) }
                    .sortedByDescending { it.width * it.height }
                good.ifEmpty { sizes.filter { max(it.width, it.height) <= cap }.sortedByDescending { it.width * it.height } }
                    .ifEmpty { sizes }
            }
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
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
        ui.flashBtn.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
        ui.flashBtn.setImageResource(R.drawable.ic_flash_off)
        ui.zoomRow.removeAllViews()
        pendingWideZoom = false

        if (!settings.frontCamera) {
            val wc = wideCam
            if (wc != null) {
                // Separate ultra-wide camera: switching lens means re-opening the camera.
                addLensChip(zoomLabel(wc.second), usingWideCam) {
                    if (!usingWideCam && !busy()) { settings.wideStart = true; bindCamera() }
                }
                addLensChip("1x", !usingWideCam) {
                    if (usingWideCam && !busy()) { settings.wideStart = false; bindCamera() }
                }
            } else {
                pendingWideZoom = true // maybe the main camera itself can zoom out; check below
            }
        }

        cam.cameraInfo.zoomState.removeObservers(this)
        cam.cameraInfo.zoomState.observe(this) { zs ->
            if (camera !== cam || !pendingWideZoom) return@observe
            pendingWideZoom = false
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
            } else if (settings.wideStart && !noWideToastShown) {
                noWideToastShown = true
                toast("This phone does not let apps use its ultra-wide lens")
            }
        }
        rotateControls(deviceRotation)
    }

    private fun zoomLabel(z: Float) = if (z < 1f) String.format("%.1fx", z) else "${z.roundToInt()}x"

    private fun addLensChip(label: String, active: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(if (active) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_circle_dark)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8); marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
        ui.zoomRow.addView(tv)
        rotateControls(deviceRotation)
    }

    private fun highlightLens(index: Int) {
        for (i in 0 until ui.zoomRow.childCount) {
            (ui.zoomRow.getChildAt(i) as? TextView)?.setTextColor(if (i == index) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun cameraId(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    /** Horizontal field of view in radians, from the lens focal length and sensor size. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun fieldOfView(info: CameraInfo): Float {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0f
        val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        return 2f * atan(max(sensor.width, sensor.height) / (2f * focal))
    }

    /** Finds a back camera clearly wider than the main one. Returns it with its zoom factor (e.g. 0.6). */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun findUltraWide(infos: List<CameraInfo>): Pair<CameraInfo, Float>? {
        val main = CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).firstOrNull() ?: return null
        val mainFov = fieldOfView(main)
        if (mainFov <= 0f) return null
        val best = infos
            .filter { it != main }
            .filter { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            .maxByOrNull { fieldOfView(it) } ?: return null
        val fov = fieldOfView(best)
        if (fov < mainFov * 1.2f) return null
        return best to (tan(mainFov / 2f) / tan(fov / 2f))
    }

    /** Fills the screen edge to edge (cropping the camera to the screen's shape) or shows 16:9. */
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
        } else {
            cropX = 1f; cropY = 1f
            lp.dimensionRatio = "$nw:$nh"
        }
        engine.setCrop(cropX, cropY)
        ui.surface.layoutParams = lp
        ui.aspectChip.text = viewModeLabel()
    }

    private fun viewModeLabel() = when (settings.viewMode) {
        "169" -> "16:9"
        "43" -> "4:3"
        else -> "FULL"
    }

    /** FULL (fills the screen) -> 16:9 -> 4:3 (widest the lens can see). */
    private fun cycleViewMode() {
        if (busy()) return
        settings.viewMode = when (settings.viewMode) {
            "full" -> "169"
            "169" -> "43"
            else -> "full"
        }
        ui.aspectChip.text = viewModeLabel()
        if ((settings.viewMode == "43") != boundWide43) bindCamera() else applyCrop()
        toast(
            when (settings.viewMode) {
                "169" -> "16:9: standard video shape"
                "43" -> "4:3: the widest view this lens can see"
                else -> "Full screen: video matches your screen shape"
            }
        )
    }

    // ================================================================== adjust panel (size, position, light)

    private fun setupAdjustPanel() {
        ui.sizeAdjust.value = (settings.personScale * 100f).coerceIn(50f, 100f)
        ui.brightAdjust.value = settings.brightness.coerceIn(-1f, 1f)
        ui.warmAdjust.value = settings.warmth.coerceIn(-1f, 1f)
        ui.sizeAdjust.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { settings.personScale = v / 100f; applyPersonAndColor() }
        }
        ui.brightAdjust.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { settings.brightness = v; applyPersonAndColor() }
        }
        ui.warmAdjust.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { settings.warmth = v; applyPersonAndColor() }
        }
        ui.autoMatchBtn.setOnClickListener { autoMatchLight() }
        ui.resetAdjustBtn.setOnClickListener {
            settings.personScale = 0.85f; settings.personOffX = 0f; settings.personOffY = 0f
            settings.brightness = 0f; settings.warmth = 0f
            syncAdjustSliders()
            applyPersonAndColor()
        }
        ui.doneAdjustBtn.setOnClickListener { setAdjustOpen(false) }
        applyPersonAndColor()
    }

    private fun syncAdjustSliders() {
        ui.sizeAdjust.value = (settings.personScale * 100f).coerceIn(50f, 100f)
        ui.brightAdjust.value = settings.brightness.coerceIn(-1f, 1f)
        ui.warmAdjust.value = settings.warmth.coerceIn(-1f, 1f)
    }

    private fun applyPersonAndColor() {
        engine.setPersonTransform(settings.personScale, settings.personOffX, settings.personOffY)
        engine.setColorAdjust(2f.pow(settings.brightness), settings.warmth)
    }

    private fun setAdjustOpen(open: Boolean) {
        adjustOpen = open
        ui.adjustPanel.visibility = if (open) View.VISIBLE else View.GONE
        ui.tuneBtn.setColorFilter(if (open) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        if (open && settings.backdrop == KEY_NONE) toast("Pick a backdrop or Blur to resize yourself")
    }

    /** Moves the person by a drag on the screen (fractions of the screen, y down). */
    private fun movePerson(dxScreen: Float, dyScreen: Float) {
        val nx = dxScreen
        val ny = -dyScreen
        // Screen axes -> world axes (the picture is turned when the phone is sideways).
        val (wx, wy) = when (deviceRotation) {
            90 -> ny to -nx
            180 -> -nx to -ny
            270 -> -ny to nx
            else -> nx to ny
        }
        settings.personOffX = (settings.personOffX + wx).coerceIn(-0.5f, 0.5f)
        settings.personOffY = (settings.personOffY + wy).coerceIn(-0.5f, 0.5f)
        applyPersonAndColor()
    }

    /** Sets brightness and warmth so you match the backdrop's light. */
    private fun autoMatchLight() {
        val bg = backdropMean
        val f = lastFraming
        if (bg == null || settings.backdrop == KEY_NONE || settings.backdrop == KEY_BLUR) {
            return toast("Choose a backdrop image first")
        }
        if (f == null || f.coverage < 0.02f) return toast("Step into the frame first")
        fun lum(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b
        val lp = lum(f.meanR, f.meanG, f.meanB).coerceAtLeast(0.02f)
        val lb = lum(bg[0], bg[1], bg[2]).coerceAtLeast(0.02f)
        val gainValue = (lb / lp).pow(0.6f).coerceIn(0.6f, 1.7f)
        val tp = (f.meanR - f.meanB) / (lp + 0.05f)
        val tb = (bg[0] - bg[2]) / (lb + 0.05f)
        settings.brightness = (ln(gainValue) / ln(2f)).coerceIn(-1f, 1f)
        settings.warmth = ((tb - tp) * 3f).coerceIn(-1f, 1f)
        syncAdjustSliders()
        applyPersonAndColor()
        toast("Matched to the backdrop. Fine-tune with the sliders.")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (adjustOpen) {
                    settings.personScale = (settings.personScale * d.scaleFactor).coerceIn(0.5f, 1f)
                    ui.sizeAdjust.value = settings.personScale * 100f
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
                focusAt(e.x / ui.surface.width, e.y / ui.surface.height)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!adjustOpen || e2.pointerCount > 1) return false
                movePerson(-distanceX / ui.surface.width, -distanceY / ui.surface.height)
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
        val pt = engine.previewToCameraPoint(x, y) ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f, p).createPoint(pt[0], pt[1])
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
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
    }

    private fun cycleQuality() {
        if (busy()) return
        settings.quality = when (settings.quality) {
            "max" -> "1080"
            "1080" -> "720"
            else -> "max"
        }
        toast(
            when (settings.quality) {
                "1080" -> "Quality: 1080p"
                "720" -> "Quality: 720p (smaller files)"
                else -> "Quality: highest available"
            }
        )
        bindCamera()
    }

    // ================================================================== segmentation / backdrops

    private fun loadSegmenter() {
        val hq = settings.highQualityCutout
        io.execute {
            try {
                engine.setSegmenter(PersonSegmenter(this, hq))
            } catch (e: Exception) {
                main.post { toast("Could not load cut-out model: ${e.message}") }
            }
        }
    }

    private fun applyEngineOptions() {
        engine.setOptions(settings.mirrorFront, settings.highQualityCutout, settings.softness)
    }

    private fun buildBackdropStrip() {
        val strip = ui.backdropStrip
        strip.removeAllViews()
        strip.addView(stripItem(KEY_NONE, null, R.drawable.ic_block, "No effect"))
        strip.addView(stripItem(KEY_BLUR, null, R.drawable.ic_blur_on, "Blur background"))
        strip.addView(stripItem(KEY_ADD, null, R.drawable.ic_add, "Add backdrop"))
        for (f in backdrops.list()) {
            val item = stripItem(f.name, f, 0, "Backdrop")
            strip.addView(item)
        }
        highlightSelection()
        rotateControls(deviceRotation)
    }

    private fun stripItem(key: String, file: java.io.File?, icon: Int, description: String): View {
        val size = dp(58)
        val frame = FrameLayout(this).apply {
            tag = key
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
            background = ContextCompat.getDrawable(context, R.drawable.bg_item)
            contentDescription = description
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            clipToOutline = true
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            if (file != null) {
                scaleType = ImageView.ScaleType.CENTER_CROP
                io.execute {
                    val t = backdrops.thumbnail(file)
                    main.post { setImageBitmap(t) }
                }
            } else {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(icon)
            }
        }
        frame.addView(iv)
        frame.setOnClickListener {
            if (key == KEY_ADD) {
                pickBackdrop.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                settings.backdrop = key
                applyBackdropSelection()
            }
        }
        if (file != null) {
            frame.setOnLongClickListener {
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
        }
        return frame
    }

    private fun highlightSelection() {
        val sel = settings.backdrop
        val strip = ui.backdropStrip
        for (i in 0 until strip.childCount) {
            val v = strip.getChildAt(i) as FrameLayout
            v.foreground = if (v.tag == sel) ContextCompat.getDrawable(this, R.drawable.fg_selected) else null
        }
    }

    private fun applyBackdropSelection() {
        highlightSelection()
        when (val key = settings.backdrop) {
            KEY_NONE -> engine.setMode(CompositeEngine.MODE_NONE)
            KEY_BLUR -> engine.setMode(CompositeEngine.MODE_BLUR)
            else -> io.execute {
                val bmp = backdrops.load(key)
                if (bmp != null) {
                    backdropMean = meanColor(bmp)
                    engine.setBackdrop(bmp)
                }
                else main.post {
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
                main.post { toast("Could not use that image") }
            }
        }
    }

    // ================================================================== recording

    private fun busy(): Boolean {
        if (recorder != null || countdown != null) {
            toast("Not while recording")
            return true
        }
        return false
    }

    private fun onRecordPressed() {
        when {
            recorder != null -> stopRecording()
            countdown != null -> cancelCountdown()
            settings.timer > 0 || settings.handsFree -> startCountdown(max(settings.timer, if (settings.handsFree) 3 else 0))
            else -> startRecording()
        }
    }

    private fun startCountdown(seconds: Int) {
        voice.stop()
        val spoken = settings.handsFree
        val tone = if (spoken) null else runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        ui.countdownText.visibility = View.VISIBLE
        setShutter(true)
        countdown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                val n = ((msLeft + 999) / 1000).toString()
                ui.countdownText.text = n
                if (spoken) speak(n) else tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
            override fun onFinish() {
                tone?.release()
                countdown = null
                ui.countdownText.visibility = View.GONE
                startRecording()
            }
        }.start()
    }

    private fun cancelCountdown() {
        if (countdown != null && settings.handsFree) {
            speak("Cancelled.") { startListening() }
        }
        countdown?.cancel()
        countdown = null
        ui.countdownText.visibility = View.GONE
        if (recorder == null) setShutter(false)
    }

    private fun startRecording() {
        val nw = engine.naturalWidth
        val nh = engine.naturalHeight
        if (nw == 0 || nh == 0) return toast("Camera is not ready yet")
        val rotation = deviceRotation
        val cw = (nw * cropX).roundToInt() and 1.inv()
        val ch = (nh * cropY).roundToInt() and 1.inv()
        val (ww, wh) = if (rotation % 180 == 0) cw to ch else ch to cw
        val size = VideoRecorder.pickEncoderSize(ww, wh)
        val audio = settings.micOn && granted(Manifest.permission.RECORD_AUDIO)
        micMonitor?.stop(); micMonitor = null
        voice.stop()
        speaker.stop()
        micMuted = false
        var recRef: VideoRecorder? = null
        val clap = if (settings.handsFree && audio) {
            ClapDetector(VideoRecorder.SAMPLE_RATE) { firstClapUs ->
                val r = recRef ?: return@ClapDetector
                // Cut the recording just before the claps so they are not in the video.
                r.cutoffUs = max(0L, firstClapUs - 300_000L)
                main.post { if (recorder === r) stopRecording() }
            }
        } else null
        try {
            val rec = VideoRecorder(
                this, size.width, size.height, audio,
                gain = { micGain },
                muted = { micMuted },
                onLevel = { level -> main.post { ui.levelBar.progress = (level * 100).toInt() } },
                onPcm = clap?.let { c -> { pcm: ShortArray, n: Int, t: Long -> c.feed(pcm, n, t) } },
            )
            recRef = rec
            rec.start()
            engine.startRecording(rec.inputSurface, size.width, size.height, rotation, { rec.ptsNs() }, { rec.paused })
            recorder = rec
        } catch (e: Exception) {
            setShutter(false)
            return toast("Cannot start recording: ${e.message}")
        }
        recordStartMs = System.currentTimeMillis()
        setShutter(true)
        ui.recPill.visibility = View.VISIBLE
        if (ui.teleBox.visibility == View.VISIBLE) {
            ui.teleScroll.scrollTo(0, 0)
            teleRunning = true
        }
        ui.levelBar.visibility = if (audio) View.VISIBLE else View.GONE
        ui.flipBtn.visibility = View.GONE
        ui.pauseBtn.visibility = View.VISIBLE
        ui.pauseBtn.setImageResource(R.drawable.ic_pause)
        setControlsEnabled(false)
        updateMicIcon()
        tickTimer()
    }

    private fun tickTimer() {
        val rec = recorder ?: return
        tickTimerOnce()
        val limit = settings.autoStop
        if (limit > 0 && rec.ptsNs() / 1_000_000_000L >= limit) {
            stopRecording()
            return
        }
        main.postDelayed({ tickTimer() }, 250)
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        setShutter(false)
        ui.recPill.visibility = View.GONE
        ui.recPill.alpha = 1f
        ui.pauseBtn.visibility = View.GONE
        teleRunning = false
        ui.flipBtn.visibility = View.VISIBLE
        setControlsEnabled(true)
        updateMicIcon()
        engine.stopRecording {
            io.execute {
                try {
                    val uri = rec.finish()
                    main.post { onVideoSaved(uri, rec) }
                } catch (e: Exception) {
                    main.post {
                        toast("Save failed: ${e.message}")
                        if (settings.handsFree) speak("Sorry, saving failed.") { startListening() }
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
        showResultSheet(uri, "${rec.width}x${rec.height}  saved to Movies/BackdropCam")
        if (settings.autoUpload) startUpload()
        if (settings.handsFree) {
            speak("Saved. Say start to record again.") {
                coach.reset()
                startListening()
            }
        }
    }

    private fun togglePause() {
        val rec = recorder ?: return
        if (rec.paused) {
            rec.resume()
            if (ui.teleBox.visibility == View.VISIBLE) teleRunning = true
            ui.pauseBtn.setImageResource(R.drawable.ic_pause)
            ui.recPill.alpha = 1f
        } else {
            rec.pause()
            teleRunning = false
            ui.pauseBtn.setImageResource(R.drawable.ic_play_arrow)
            ui.recPill.alpha = 0.7f
        }
        tickTimerOnce()
    }

    private fun tickTimerOnce() {
        val rec = recorder ?: return
        val s = rec.ptsNs() / 1_000_000_000L
        var t = String.format("%02d:%02d", s / 60, s % 60)
        val limit = settings.autoStop
        if (limit > 0) t += String.format(" / %02d:%02d", limit / 60, limit % 60)
        ui.recTime.text = if (rec.paused) "$t  Paused" else t
    }

    /** Big red circle when idle; small rounded "stop" square while recording, like the system camera. */
    private fun setShutter(recording: Boolean) {
        val lp = ui.recordInner.layoutParams
        val size = dp(if (recording) 32 else 64)
        lp.width = size
        lp.height = size
        ui.recordInner.layoutParams = lp
        ui.recordInner.background = ContextCompat.getDrawable(
            this, if (recording) R.drawable.shutter_recording else R.drawable.shutter_idle
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.35f
        for (v in listOf(ui.settingsBtn, ui.flipBtn, ui.qualityChip, ui.aspectChip, ui.timerBtn, ui.lastThumb, ui.zoomRow)) {
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
        toast(if (settings.timer == 0) "Timer off" else "Recording starts ${settings.timer}s after you press record")
    }

    private fun updateTimerUi() {
        val t = settings.timer
        ui.timerBtn.setImageResource(if (t == 0) R.drawable.ic_timer_off else R.drawable.ic_timer)
        ui.timerBadge.text = if (t == 0) "" else "${t}s"
    }

    // ================================================================== microphone

    private fun onMicPressed() {
        if (recorder != null) {
            micMuted = !micMuted
            updateMicIcon()
            toast(if (micMuted) "Mic muted" else "Mic on")
        } else {
            showMicSheet()
        }
    }

    private fun updateMicIcon() {
        val off = if (recorder != null) micMuted else !settings.micOn
        ui.micBtn.setImageResource(if (off) R.drawable.ic_mic_off else R.drawable.ic_mic)
    }

    private fun showMicSheet() {
        val b = SheetMicBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        b.micSwitch.isChecked = settings.micOn
        b.gainSlider.value = (settings.micGain * 100f).coerceIn(0f, 200f).let { (it / 5f).toInt() * 5f }
        b.gainValue.text = "${b.gainSlider.value.toInt()}%"
        b.micSwitch.setOnCheckedChangeListener { _, on ->
            settings.micOn = on
            updateMicIcon()
        }
        b.gainSlider.addOnChangeListener { _, value, _ ->
            micGain = value / 100f
            settings.micGain = micGain
            b.gainValue.text = "${value.toInt()}%"
        }
        voice.stop()
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            micMonitor = MicMonitor({ micGain }) { level ->
                main.post { b.levelMeter.setProgressCompat((level * 100).toInt(), true) }
            }.also { it.start() }
        }
        dlg.setOnDismissListener {
            micMonitor?.stop()
            micMonitor = null
            if (settings.handsFree && recorder == null) startListening()
        }
        dlg.show()
    }

    // ================================================================== settings

    private fun showSettings() {
        if (busy()) return
        val b = SheetSettingsBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)

        b.qualityGroup.check(
            when (settings.quality) {
                "1080" -> R.id.q1080
                "720" -> R.id.q720
                else -> R.id.qMax
            }
        )
        b.qualityGroup.setOnCheckedStateChangeListener { _, ids ->
            val q = when (ids.firstOrNull()) {
                R.id.q1080 -> "1080"
                R.id.q720 -> "720"
                else -> "max"
            }
            if (q != settings.quality) {
                settings.quality = q
                bindCamera()
            }
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
        b.mirrorSwitch.isChecked = settings.mirrorFront
        b.mirrorSwitch.setOnCheckedChangeListener { _, on ->
            settings.mirrorFront = on
            applyEngineOptions()
        }
        b.wideSwitch.isChecked = settings.wideStart
        b.wideSwitch.setOnCheckedChangeListener { _, on ->
            settings.wideStart = on
            if (!settings.frontCamera) bindCamera()
        }
        b.handsFreeSwitch.isChecked = settings.handsFree
        b.handsFreeSwitch.setOnCheckedChangeListener { _, on -> setHandsFree(on, announce = true) }
        val stopIds = mapOf(0 to R.id.as0, 30 to R.id.as30, 60 to R.id.as60, 90 to R.id.as90, 120 to R.id.as120, 150 to R.id.as150, 180 to R.id.as180)
        b.autoStopGroup.check(stopIds[settings.autoStop] ?: R.id.as0)
        b.autoStopGroup.setOnCheckedStateChangeListener { _, ids ->
            settings.autoStop = stopIds.entries.firstOrNull { it.value == ids.firstOrNull() }?.key ?: 0
        }
        b.autoUploadSwitch.isChecked = settings.autoUpload
        b.autoUploadSwitch.setOnCheckedChangeListener { _, on -> settings.autoUpload = on }
        dlg.show()
    }

    // ================================================================== result sheet and Drive

    private fun showResultSheet(uri: Uri, details: String? = null) {
        val b = SheetResultBinding.inflate(layoutInflater)
        val dlg = BottomSheetDialog(this)
        dlg.setContentView(b.root)
        resultUi = b
        b.details.text = details ?: "Saved to Movies/BackdropCam"
        loadThumbInto(uri, b.thumb)
        b.playBtn.setOnClickListener {
            startActivitySafe(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        b.shareVideoBtn.setOnClickListener {
            startActivitySafe(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share video"
                )
            )
        }
        b.uploadBtn.setOnClickListener { startUpload() }
        b.copyLinkBtn.setOnClickListener { lastLink?.let { copyLink(it) } }
        b.shareLinkBtn.setOnClickListener {
            lastLink?.let {
                startActivitySafe(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), "Share link")
                )
            }
        }
        dlg.setOnDismissListener { if (resultUi === b) resultUi = null }
        refreshResultUi()
        dlg.show()
    }

    private fun refreshResultUi() {
        val b = resultUi ?: return
        b.uploadBtn.isEnabled = !uploading
        b.uploadBtn.text = when {
            uploading -> "Uploading..."
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
        uploadMessage = "Connecting to Google Drive..."
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
                    val token = result.accessToken
                    if (token != null) upload(token) else uploadFinished(null, "Google sign-in failed")
                }
            }
            .addOnFailureListener { uploadFinished(null, "Google sign-in failed: ${it.message}") }
    }

    private fun upload(token: String) {
        val uri = lastVideo ?: return uploadFinished(null, null)
        uploadMessage = "Uploading..."
        refreshResultUi()
        uploadExecutor.execute {
            try {
                val name = "BackdropCam_${System.currentTimeMillis()}.mp4"
                val link = DriveUploader(contentResolver, token).uploadAndShare(uri, name) { pct ->
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
            if (resultUi == null) {
                Snackbar.make(ui.root, "Uploaded. Link copied.", Snackbar.LENGTH_LONG)
                    .setAction("Share") {
                        startActivitySafe(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share link")
                        )
                    }.show()
            }
        } else if (message != null && resultUi == null) {
            toast(message)
        }
        refreshResultUi()
    }

    private fun copyLink(link: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Video link", link))
        toast("Link copied")
    }

    // ================================================================== hands-free

    private fun setHandsFree(on: Boolean, announce: Boolean) {
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) {
            toast("Hands-free needs microphone permission")
            return
        }
        settings.handsFree = on
        engine.framingWanted = on
        updateHandsFreeUi()
        coach.reset()
        if (on) {
            if (!voice.available) toast("Voice commands are not available on this phone; guidance still works")
            if (announce) {
                speak("Hands-free on. I will guide you into the frame. Say start to record, and clap twice to stop.") {
                    startListening()
                }
            } else startListening()
        } else {
            voice.stop()
            if (announce) speak("Hands-free off.")
        }
    }

    private fun updateHandsFreeUi() {
        val on = settings.handsFree
        ui.handsBtn.alpha = if (on) 1f else 0.55f
        ui.handsBtn.setColorFilter(if (on) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
    }

    /**
     * Says [text] out loud. The microphone stops listening while the phone speaks, so it can never
     * hear its own "say start" and trigger itself; it resumes shortly after.
     */
    private fun speak(text: String, done: (() -> Unit)? = null) {
        voice.stop()
        speaker.say(text) {
            done?.invoke()
            main.postDelayed({ startListening() }, 700)
        }
    }

    private fun startListening() {
        if (settings.handsFree && recorder == null && countdown == null && !isFinishing) voice.start()
    }

    private fun onVoiceStart() {
        if (recorder != null || countdown != null) return
        speak("Okay. Recording in") { if (recorder == null && countdown == null) startCountdown(3) }
    }

    // ================================================================== teleprompter

    private val teleTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = if (teleLastMs == 0L) 0f else (now - teleLastMs) / 1000f
            teleLastMs = now
            if (teleRunning && ui.teleBox.visibility == View.VISIBLE) {
                teleAccum += settings.teleSpeed * dp(9) * dt
                val step = teleAccum.toInt()
                if (step > 0) {
                    ui.teleScroll.scrollBy(0, step)
                    teleAccum -= step
                }
            }
            main.postDelayed(this, 16)
        }
    }

    private fun updateTeleUi() {
        val front = settings.frontCamera
        ui.teleBtn.visibility = if (front) View.VISIBLE else View.GONE
        val script = settings.teleScript?.let { scripts.load(it) }
        val show = front && settings.teleOn && script != null && script.body.isNotBlank()
        ui.teleBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) teleRunning = false
        if (script != null) ui.teleText.text = script.body
        ui.teleText.textSize = settings.teleSize.toFloat()
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
            importScript.launch(
                arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            )
        }
        val refresh = {
            b.scriptList.removeAllViews()
            val list = scripts.list()
            if (list.isEmpty()) {
                b.scriptList.addView(TextView(this).apply {
                    text = "No scripts yet. Tap New script or Import."
                    setTextColor(0xB3FFFFFF.toInt())
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            for (sc in list) {
                val active = sc.id == settings.teleScript
                b.scriptList.addView(TextView(this).apply {
                    text = (if (active) "✓  " else "") + sc.title
                    textSize = 16f
                    setTextColor(if (active) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
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
                val newId = scripts.save(id, b.scriptTitle.text.toString(), b.scriptBody.text.toString())
                settings.teleScript = newId
                settings.teleOn = true
                updateTeleUi()
                refreshScriptList?.invoke()
                if (!settings.frontCamera) toast("Script saved. Switch to the front camera to use the teleprompter.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Text shared from Google Docs, Notes, WhatsApp... becomes a new teleprompter script. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared script"
        intent.action = null
        editScript(null, title, text)
    }

    // ================================================================== helpers

    private fun meanColor(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (small !== bmp) small.recycle()
        var r = 0f; var g = 0f; var b = 0f
        for (c in px) {
            r += ((c shr 16) and 0xFF) / 255f
            g += ((c shr 8) and 0xFF) / 255f
            b += (c and 0xFF) / 255f
        }
        return floatArrayOf(r / px.size, g / px.size, b / px.size)
    }

    /** Keeps icons upright when the phone is turned, like the built-in camera app. */
    private fun rotateControls(rotation: Int) {
        val angle = when (rotation) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }
        val views = mutableListOf<View>(
            ui.settingsBtn, ui.flashBtn, ui.timerBox, ui.handsBtn, ui.aspectChip, ui.qualityChip, ui.micBtn, ui.pauseBtn, ui.tuneBtn, ui.teleBtn,
            ui.flipBtn, ui.lastThumb, ui.countdownText, ui.recPill
        )
        for (i in 0 until ui.zoomRow.childCount) views += ui.zoomRow.getChildAt(i)
        for (i in 0 until ui.backdropStrip.childCount) views += ui.backdropStrip.getChildAt(i)
        for (v in views) v.animate().rotation(angle).setDuration(200).start()
    }

    private fun loadThumbInto(uri: Uri, target: ImageView) {
        io.execute {
            val bmp: Bitmap? = try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val f = r.getFrameAtTime(0)
                r.release()
                f
            } catch (e: Exception) {
                null
            }
            main.post { if (bmp != null) target.setImageBitmap(bmp) }
        }
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No app found for this")
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_NONE = "none"
        private const val KEY_BLUR = "blur"
        private const val KEY_ADD = "add"
    }
}
