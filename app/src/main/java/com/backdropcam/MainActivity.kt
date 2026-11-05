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
import androidx.core.widget.TextViewCompat
import com.backdropcam.databinding.ActivityMainBinding
import com.backdropcam.databinding.DialogScriptBinding
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
    private var noWideHintShown = false
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
            else hint("BackdropCam needs camera access. Allow it in the phone's app settings.")
        }

    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addBackdrop(uri) }

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
            val token = runCatching {
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken
            }.getOrNull()
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
        scripts = ScriptStore(this)
        micGain = settings.micGain
        lastVideo = settings.lastVideo?.let(Uri::parse)

        engine = CompositeEngine(this).apply {
            onGeometry = { w, h -> onCameraGeometry(w, h) }
            onFraming = { f ->
                lastFraming = f
                if (settings.handsFree && recorder == null && countdown == null && !speaker.isSpeaking) coach.update(f)
            }
            framingWanted = settings.handsFree
        }
        speaker = Speaker(this)
        coach = FramingCoach { speak(it) }
        voice = KeywordSpotter(this) { word, atUs -> main.post { onKeyword(word, atUs) } }
        // Whenever the phone stops talking (for any reason) the ear opens again shortly after.
        speaker.onIdle = {
            voice.mutedUntil = System.currentTimeMillis() + 700
            startListening()
        }
        if (settings.handsFree) loadVoice()
        applyEngineOptions()
        loadSegmenter()

        setupWindow()
        setupPreviewSurface()
        setupGestures()
        setupButtons()
        setupAdjustPanel()

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
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        startListening()
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        orientationListener.disable()
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
        ui.teleBtn.setOnClickListener { showScriptsSheet() }
        // Tap the script to pause / resume scrolling (also handy for rehearsing).
        ui.teleBox.setOnClickListener { teleRunning = !teleRunning }
        ui.teleText.setOnClickListener { teleRunning = !teleRunning }
        ui.recordBtn.setOnClickListener { onRecordPressed() }
        ui.pauseBtn.setOnClickListener { togglePause() }
        ui.flipBtn.setOnClickListener { flipCamera() }
        ui.lastThumb.setOnClickListener { lastVideo?.let { showResultSheet(it) } ?: hint("No recordings yet") }
        ui.lastThumb.roundCorners(dp(14))
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
            val p = Preview.Builder()
                .setResolutionSelector(CameraLenses.resolutionSelector(settings.qualityCap, fourByThree))
                .setTargetRotation(Surface.ROTATION_0)
                .build()
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
            } else if (settings.wideStart && !noWideHintShown) {
                noWideHintShown = true
                hint("This phone does not let apps use its ultra-wide lens")
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

    /** FULL -> 16:9 -> 4:3 (the widest view the lens can see). */
    private fun cycleViewMode() {
        if (busy()) return
        settings.viewMode = when (settings.viewMode) {
            "full" -> "169"
            "169" -> "43"
            else -> "full"
        }
        ui.aspectChip.text = viewModeLabel()
        if ((settings.viewMode == "43") != boundFourByThree) bindCamera() else applyCrop()
        hint(
            when (settings.viewMode) {
                "169" -> "16:9, the standard video shape"
                "43" -> "4:3, the widest view this lens can see"
                else -> "Full screen, the video matches your screen"
            }
        )
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
                focusAt(e.x, e.y)
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
                engine.setSegmenter(PersonSegmenter(this, hq))
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
                setTextColor(0xCCFFFFFF.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            tile.addView(col)
        }
        tile.setOnClickListener {
            if (key == KEY_ADD) {
                if (!busy()) pickBackdrop.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        when (val key = settings.backdrop) {
            KEY_NONE -> engine.setMode(CompositeEngine.MODE_NONE)
            KEY_BLUR -> engine.setMode(CompositeEngine.MODE_BLUR)
            else -> io.execute {
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
                main.post { hint("Could not use that image") }
            }
        }
    }

    private fun setupAdjustPanel() {
        syncAdjustSliders()
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

    /** The adjust panel replaces the lens switch and backdrop strip while open, to save space. */
    private fun setAdjustOpen(open: Boolean) {
        adjustOpen = open
        ui.adjustPanel.visibility = if (open) View.VISIBLE else View.GONE
        ui.stripScroll.visibility = if (open) View.GONE else View.VISIBLE
        updateLensRowVisibility()
        setActive(ui.tuneBtn, open)
        if (open && settings.backdrop == KEY_NONE) hint("Pick a backdrop to resize or move yourself")
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
        syncAdjustSliders()
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
        val rotation = deviceRotation
        val cw = (nw * cropX).roundToInt() and 1.inv()
        val ch = (nh * cropY).roundToInt() and 1.inv()
        val (ww, wh) = if (rotation % 180 == 0) cw to ch else ch to cw
        val size = VideoRecorder.pickEncoderSize(ww, wh)
        val audio = settings.micOn && granted(Manifest.permission.RECORD_AUDIO)
        val listenForStop = settings.handsFree && audio

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
                onLevel = { level -> main.post { ui.levelBar.progress = (level * 100).toInt() } },
                // In hands-free mode the recording's own sound is checked for "stop".
                onPcm = if (listenForStop) { pcm: ShortArray, n: Int, t: Long -> voice.feed48k(pcm, n, t) } else null,
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
        if (settings.autoUpload) startUpload()
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
        ui.timerChip.compoundDrawablePadding = if (t == 0) 0 else dp(4)
        ui.timerChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (t == 0) R.drawable.ic_timer_off else R.drawable.ic_timer, 0, 0, 0
        )
        val color = if (t == 0) 0xFFFFFFFF.toInt() else ContextCompat.getColor(this, R.color.accent)
        ui.timerChip.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(ui.timerChip, ColorStateList.valueOf(color))
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
        b.details.text = details ?: "Movies/BackdropCam"
        b.thumb.roundCorners(dp(16))
        loadThumbInto(uri, b.thumb)
        b.playBtn.setOnClickListener {
            startActivitySafe(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
        b.shareLinkBtn.setOnClickListener { lastLink?.let { shareText(it) } }
        dlg.setOnDismissListener { if (resultUi === b) resultUi = null }
        refreshResultUi()
        dlg.show()
    }

    private fun refreshResultUi() {
        val b = resultUi ?: return
        b.uploadBtn.isEnabled = !uploading
        b.uploadBtn.text = when {
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
            .addOnFailureListener { uploadFinished(null, "Google sign-in failed: ${it.message}") }
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
            hint("Hands-free on: say “start” to record, “stop” to finish")
            speak("Hands-free on. I will guide you into the frame. Say start to record, and say stop to finish.")
            loadVoice()
        } else {
            speaker.stop()
            voice.unload()
            hint("Hands-free off")
        }
    }

    /** Loads the offline speech model (about 100 MB of memory), so only while hands-free is on. */
    private fun loadVoice() {
        if (voice.available) return
        if (!File(filesDir, "vosk-en").exists()) hint("Preparing voice commands, this takes a moment the first time")
        voice.load { ok ->
            main.post {
                when {
                    !settings.handsFree -> voice.unload() // switched off again while loading
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
        if (foreground && settings.handsFree && recorder == null && micMonitor == null) voice.start()
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
        val show = front && settings.teleOn && script != null && script.body.isNotBlank()
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
            importScript.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
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
        // Icons and tiles turn; text (chips, timer pill, hints) stays readable in place.
        val views = mutableListOf<View>(
            ui.settingsBtn, ui.flashBtn, ui.micBtn, ui.handsBtn, ui.tuneBtn, ui.teleBtn,
            ui.pauseBtn, ui.flipBtn, ui.lastThumb, ui.countdownText
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
                    getFrameAtTime(0).also { release() }
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
    }
}
