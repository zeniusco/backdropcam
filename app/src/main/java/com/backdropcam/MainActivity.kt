package com.backdropcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.backdropcam.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var engine: CompositeEngine
    private lateinit var segmenter: PersonSegmenter
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var front = false
    private var recorder: VideoRecorder? = null
    private var recordingStartMs = 0L
    private var lastVideo: Uri? = null
    private var shareLink: String? = null

    /** Longest-side caps for the camera resolution. */
    private val qualities = listOf("Max" to 3840, "1080p" to 1920, "720p" to 1280)
    private var qualityIndex = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) bindCamera()
            else toast("Camera permission is required")
        }

    private val pickBackdrop =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadBackdrop(uri)
        }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            try {
                val auth = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                auth.accessToken?.let { upload(it) } ?: setStatus("Google sign-in did not return a token")
            } catch (e: Exception) {
                setStatus("Google sign-in cancelled")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        engine = CompositeEngine()
        segmenter = PersonSegmenter(this) { data, w, h -> engine.updateMask(data, w, h) }
        engine.onOutputSize = { w, h ->
            (ui.surface.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "$w:$h"
            ui.surface.requestLayout()
            ui.infoText.text = "${if (front) "Front" else "Back"} camera  ${max(w, h)}p source  ${w}x$h"
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

        ui.backdropBtn.setOnClickListener {
            pickBackdrop.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        ui.flipBtn.setOnClickListener {
            if (recorder != null) return@setOnClickListener toast("Stop recording before switching camera")
            front = !front
            segmenter.reset()
            bindCamera()
        }
        ui.qualityBtn.setOnClickListener {
            if (recorder != null) return@setOnClickListener toast("Stop recording first")
            qualityIndex = (qualityIndex + 1) % qualities.size
            ui.qualityBtn.text = qualities[qualityIndex].first
            bindCamera()
        }
        ui.recordBtn.setOnClickListener { if (recorder == null) startRecording() else stopRecording() }
        ui.uploadBtn.setOnClickListener { startUpload() }
        ui.shareBtn.setOnClickListener { shareLink?.let { shareText(it) } }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            bindCamera()
        } else {
            permissionLauncher.launch(needed)
        }
    }

    // ------------------------------------------------------------------ camera

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()

            val preview = Preview.Builder()
                .setResolutionSelector(highestResolutionSelector(qualities[qualityIndex].second))
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            preview.setSurfaceProvider(engine.surfaceProvider(front))

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { it.setAnalyzer(analysisExecutor, segmenter) }

            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Picks the largest 16:9 sensor output the camera offers (up to [cap] on the long side)
     * that the device's video encoder can also produce, so the recording keeps full quality.
     */
    private fun highestResolutionSelector(cap: Int): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionFilter { sizes, _ ->
                val good = sizes
                    .filter { max(it.width, it.height) <= cap }
                    .filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f }
                    .filter { VideoRecorder.isSizeSupported(it.width, it.height) }
                    .sortedByDescending { it.width * it.height }
                good.ifEmpty { sizes.sortedByDescending { it.width * it.height } }
            }
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

    // ------------------------------------------------------------------ backdrop

    private fun loadBackdrop(uri: Uri) {
        ioExecutor.execute {
            try {
                val bmp: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > 4096) {
                        val s = 4096f / longest
                        dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                    }
                }
                engine.setBackdrop(bmp)
            } catch (e: Exception) {
                mainHandler.post { toast("Could not load image: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ recording

    private fun startRecording() {
        val w = engine.outputWidth and 1.inv()
        val h = engine.outputHeight and 1.inv()
        if (w == 0 || h == 0) return toast("Camera is not ready yet")
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        try {
            val rec = VideoRecorder(contentResolver, w, h, audio)
            rec.start()
            engine.startRecording(rec.inputSurface, w, h, rec.startNs)
            recorder = rec
        } catch (e: Exception) {
            return toast("Cannot start recording: ${e.message}")
        }
        ui.resultPanel.visibility = View.GONE
        ui.recordBtn.text = "Stop"
        ui.timerText.visibility = View.VISIBLE
        recordingStartMs = System.currentTimeMillis()
        tickTimer()
    }

    private fun tickTimer() {
        if (recorder == null) return
        val s = (System.currentTimeMillis() - recordingStartMs) / 1000
        ui.timerText.text = "%02d:%02d".format(s / 60, s % 60)
        mainHandler.postDelayed({ tickTimer() }, 500)
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        ui.recordBtn.text = "Record"
        ui.timerText.visibility = View.GONE
        showPanel("Saving video...")
        engine.stopRecording {
            ioExecutor.execute {
                try {
                    val uri = rec.finish()
                    lastVideo = uri
                    mainHandler.post {
                        showPanel("Saved to Gallery (Movies/BackdropCam)")
                        startUpload()
                    }
                } catch (e: Exception) {
                    mainHandler.post { showPanel("Save failed: ${e.message}") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Google Drive

    private fun startUpload() {
        if (lastVideo == null) return
        setStatus("Signing in to Google...")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    authLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else {
                    result.accessToken?.let { upload(it) }
                }
            }
            .addOnFailureListener { setStatus("Google sign-in failed: ${it.message}") }
    }

    private fun upload(token: String) {
        val uri = lastVideo ?: return
        ui.progress.visibility = View.VISIBLE
        ui.progress.progress = 0
        ui.uploadBtn.isEnabled = false
        setStatus("Uploading to Google Drive...")
        ioExecutor.execute {
            try {
                val link = DriveUploader(contentResolver, token)
                    .uploadAndShare(uri, "BackdropCam_${System.currentTimeMillis()}.mp4") { pct ->
                        mainHandler.post { ui.progress.progress = pct }
                    }
                shareLink = link
                mainHandler.post {
                    ui.progress.visibility = View.GONE
                    ui.uploadBtn.isEnabled = true
                    ui.shareBtn.isEnabled = true
                    setStatus("Uploaded. Link copied:\n$link")
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Drive link", link))
                }
            } catch (e: Exception) {
                mainHandler.post {
                    ui.progress.visibility = View.GONE
                    ui.uploadBtn.isEnabled = true
                    setStatus("Upload failed: ${e.message}")
                }
            }
        }
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Share video link"
            )
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun showPanel(text: String) {
        ui.resultPanel.visibility = View.VISIBLE
        setStatus(text)
    }

    private fun setStatus(text: String) {
        ui.resultPanel.visibility = View.VISIBLE
        ui.statusText.text = text
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (recorder != null) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
        analysisExecutor.execute { segmenter.close() }
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
    }
}
