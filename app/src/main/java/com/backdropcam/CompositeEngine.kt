package com.backdropcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OpenGL ES 3.0 compositing pipeline on its own thread.
 *
 * Coordinate spaces (all normalised 0..1, y pointing up):
 *  - N "natural": the camera picture upright for the phone held in portrait.
 *  - W "world":   N rotated by how the user holds the phone, so people are always upright.
 *                 Recordings are made in W, so a phone held sideways records landscape.
 *
 * Every camera frame:
 *  1. is copied (upright, full resolution) into a small ring of frames;
 *  2. a square crop around the person is cut from it and segmented on another thread;
 *  3. the frame is shown and recorded only when ITS OWN mask is ready, so the cut-out never
 *     trails behind movement. Frames keep the timestamp of their capture, so sound stays in sync.
 */
class CompositeEngine(context: Context) {

    private val appContext = context.applicationContext
    private val thread = HandlerThread("composite-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }
    private val segExecutor = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------ EGL
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0
    private var encoderW = 0
    private var encoderH = 0
    private var recordPts: () -> Long = { 0L }
    private var recordPaused: () -> Boolean = { false }
    // Part of the natural (portrait) frame that is shown and recorded, e.g. to fill a tall screen.
    private var cropNx = 1f
    private var cropNy = 1f

    // ------------------------------------------------------------------ GL objects
    private var copyProgram = 0  // camera (external texture) -> upright frame in the ring
    private var program = 0      // everything else
    private var oesTex = 0
    private var maskTex = 0
    private var bgTex = 0
    private var smallTex = 0     // small copy of the whole picture, source of the blur effect
    private var smallFbo = 0
    private var smallW = 0
    private var smallH = 0
    private var logoTex = 0
    private var logoReady = false
    private var logoAspect = 1f  // height / width of the logo image
    private var logo = LogoPlacement()
    private var logoOn = false
    private var overlayTex = 0
    private var overlayOn = false
    private var roiTex = 0       // square crop around the person, input of the segmentation
    private var roiFbo = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private val locs = HashMap<String, Int>()
    private val copyLocs = HashMap<String, Int>()

    // ------------------------------------------------------------------ frame ring
    private class Slot(val tex: Int, val fbo: Int) {
        var id = 0L
        var pending = false
        var capturedMs = 0L
        var encode = false
        var ptsNs = 0L
    }

    private val ring = ArrayList<Slot>()
    private var ringW = 0
    private var ringH = 0
    private var frameCounter = 0L

    // ------------------------------------------------------------------ camera geometry
    private var bufW = 0
    private var bufH = 0
    private var sensorRotation = 90
    private var isFront = false
    private var geometryReady = false
    private val stMatrix = FloatArray(16)
    private var fixMatrix = identity()
    private var nFromW = identity()

    @Volatile var naturalWidth = 0; private set
    @Volatile var naturalHeight = 0; private set

    /** Main-thread callback with the natural (portrait) camera size once known. */
    var onGeometry: ((width: Int, height: Int) -> Unit)? = null

    // ------------------------------------------------------------------ options (GL thread)
    private var mode = MODE_BLUR
    private var deviceRotation = 0
    private var rotationLocked = false
    private var mirrorFront = true
    private var refine = true
    private var edgeLo = 0.3f
    private var edgeHi = 0.7f
    private var bgAspect = 1f
    private var bgReady = false

    // Person layer: size (1 = as filmed), offset in world units, colour adjustment.
    private var personScale = 1f
    private var personOffX = 0f
    private var personOffY = 0f
    private var gain = 1f
    private var warmth = 0f

    // ------------------------------------------------------------------ segmentation
    @Volatile private var segmenter: PersonSegmenter? = null
    private val segBusy = AtomicBoolean(false)
    private var segBitmap: Bitmap? = null
    private val readBuf: ByteBuffer = ByteBuffer.allocateDirect(ROI_SIZE * ROI_SIZE * 4).order(ByteOrder.nativeOrder())
    private var maskGen = 0
    // Two masks: one of the whole picture (always available, a little coarser) and one of the
    // crop around the person (detailed). The crop mask is used inside the crop and blends into
    // the whole-picture mask at its border, so a crop that is too tight can never cut you off.
    private var maskFullTex = 0
    private var fullReady = false
    private val fullRect = floatArrayOf(0f, 0f, 1f, 1f)
    private var roiReady = false
    private val roiRect = floatArrayOf(0f, 0f, 1f, 1f)
    private var segCount = 0L
    /** Crop to segment next, decided from the last mask (null = whole picture). */
    @Volatile private var nextRoi: FloatArray? = null
    private var lastRoi: FloatArray? = null
    private var lastFullRoi: FloatArray? = null

    /** Set true to keep segmenting (for framing guidance) even with no background effect. */
    @Volatile var framingWanted = false

    /** Main-thread callback with where the person is in the frame. */
    var onFraming: ((Framing) -> Unit)? = null
    private var lastFramingMs = 0L

    /**
     * Where the person is, in the recorded picture (0..1, y down).
     * [mirrored] is true when the picture is a mirror image (front camera, mirror on).
     */
    class Framing(
        val coverage: Float,
        val centerX: Float,
        val top: Float,
        val bottom: Float,
        val mirrored: Boolean,
        /** Average colour of the person (0..1), for brightness matching. */
        val meanR: Float = 0f,
        val meanG: Float = 0f,
        val meanB: Float = 0f,
    )

    /** Maps preview (screen) uv to camera buffer uv. Used for tap-to-focus. */
    @Volatile private var focusMatrix: FloatArray? = null

    init {
        handler.post { initGl() }
    }

    // ================================================================== public API

    fun setPreviewSurface(surface: Surface, w: Int, h: Int) {
        handler.post {
            destroyPreview()
            previewSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            previewW = w; previewH = h
            // Frames can come in short bursts (when a mask arrives); never wait for the display.
            EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext)
            EGL14.eglSwapInterval(display, 0)
            makeCurrentAny()
        }
    }

    fun clearPreviewSurface() {
        handler.post { destroyPreview() }
    }

    fun surfaceProvider(front: Boolean, sensorRotationDegrees: Int) = Preview.SurfaceProvider { request ->
        handler.post { attachCamera(request, front, sensorRotationDegrees) }
    }

    /** Swaps the segmentation model. The old one is closed on the segmentation thread. */
    fun setSegmenter(newSegmenter: PersonSegmenter) {
        try {
            segExecutor.execute {
                val old = segmenter
                segmenter = newSegmenter
                old?.close()
            }
            handler.post { invalidateMask() }
        } catch (e: RejectedExecutionException) {
            newSegmenter.close() // the engine was released meanwhile
        }
    }

    fun setMode(newMode: Int) {
        handler.post {
            if (newMode != MODE_IMAGE) stopBackdropVideo() // no need to keep decoding it
            mode = newMode
        }
    }

    // Video backdrop: a looping, muted MediaPlayer drawing into its own external texture.
    private var bgVideoTex = 0
    private var bgVideoSt: SurfaceTexture? = null
    private var bgVideoSurface: Surface? = null
    private var bgPlayer: android.media.MediaPlayer? = null
    private var bgIsVideo = false
    private val bgVideoMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Plays [path] (a video file) as a looping, silent backdrop. */
    fun setBackdropVideo(path: String, aspect: Float) {
        handler.post {
            stopBackdropVideo()
            makeCurrentAny()
            val st = SurfaceTexture(bgVideoTex)
            st.setOnFrameAvailableListener({
                runCatching {
                    makeCurrentAny()
                    it.updateTexImage()
                    it.getTransformMatrix(bgVideoMatrix)
                    bgReady = true
                }
            }, handler)
            val surface = Surface(st)
            bgVideoSt = st
            bgVideoSurface = surface
            try {
                bgPlayer = android.media.MediaPlayer().apply {
                    setDataSource(path)
                    setSurface(surface)
                    isLooping = true
                    setVolume(0f, 0f)
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
                bgAspect = aspect
                bgIsVideo = true
                mode = MODE_IMAGE
            } catch (e: Exception) {
                Log.e(TAG, "backdrop video failed", e)
                stopBackdropVideo()
            }
        }
    }

    /** Pauses / resumes a video backdrop (with the app in the background). */
    fun setBackdropVideoPlaying(play: Boolean) {
        handler.post {
            val p = bgPlayer ?: return@post
            runCatching { if (play) p.start() else p.pause() }
        }
    }

    private fun stopBackdropVideo() {
        bgPlayer?.run { runCatching { stop() }; release() }
        bgPlayer = null
        bgVideoSurface?.release(); bgVideoSurface = null
        bgVideoSt?.release(); bgVideoSt = null
        bgIsVideo = false
    }

    fun setBackdrop(bitmap: Bitmap) {
        handler.post {
            stopBackdropVideo()
            makeCurrentAny()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            setLinear(GLES30.GL_TEXTURE_2D)
            bgAspect = bitmap.width.toFloat() / bitmap.height
            bgReady = true
            mode = MODE_IMAGE
        }
    }

    /** Logo image (keeps transparency), or null to remove it. */
    fun setLogo(bitmap: Bitmap?) {
        handler.post {
            if (bitmap == null) {
                logoReady = false
                return@post
            }
            makeCurrentAny()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logoTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            setLinear(GLES30.GL_TEXTURE_2D)
            logoAspect = bitmap.height.toFloat() / bitmap.width
            logoReady = true
        }
    }

    /**
     * Text and graphics drawn on top of everything (name title, intro card, captions), covering
     * the visible picture exactly. Null clears it. The bitmap is recycled once uploaded.
     */
    fun setOverlay(bitmap: Bitmap?) {
        handler.post {
            if (bitmap == null) {
                overlayOn = false
                return@post
            }
            makeCurrentAny()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, overlayTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            setLinear(GLES30.GL_TEXTURE_2D)
            bitmap.recycle()
            overlayOn = true
        }
    }

    fun setLogoPlacement(on: Boolean, placement: LogoPlacement) {
        handler.post {
            logoOn = on
            logo = placement
        }
    }

    fun setOptions(mirrorFrontCamera: Boolean, refineEdges: Boolean, softness: Float) {
        handler.post {
            if (mirrorFront != mirrorFrontCamera) invalidateMask()
            mirrorFront = mirrorFrontCamera
            refine = refineEdges
            val s = softness.coerceIn(0f, 1f)
            edgeLo = 0.5f - (0.04f + 0.36f * s)
            edgeHi = 0.5f + (0.04f + 0.36f * s)
        }
    }

    /** Device rotation in degrees (0, 90, 180, 270). Ignored while recording. */
    fun setDeviceRotation(degrees: Int) {
        handler.post {
            if (!rotationLocked && degrees != deviceRotation) {
                presentPendingUpTo(Long.MAX_VALUE)
                deviceRotation = degrees
                invalidateMask()
            }
        }
    }

    /**
     * Draws the person smaller ([scale] < 1) on top of the backdrop, standing on the bottom edge,
     * moved by ([offX], [offY]) in fractions of the frame (y up).
     */
    fun setPersonTransform(scale: Float, offX: Float, offY: Float) {
        handler.post {
            personScale = scale.coerceIn(0.3f, 1f)
            personOffX = offX.coerceIn(-0.5f, 0.5f)
            personOffY = offY.coerceIn(-0.5f, 0.5f)
        }
    }

    /** [gainValue] multiplies the person's brightness; [warmthValue] -1 (cooler) .. 1 (warmer). */
    fun setColorAdjust(gainValue: Float, warmthValue: Float) {
        handler.post {
            gain = gainValue.coerceIn(0.3f, 3f)
            warmth = warmthValue.coerceIn(-1f, 1f)
        }
    }

    /** Show and record only the centre [sx] x [sy] part of the portrait camera frame. */
    fun setCrop(sx: Float, sy: Float) {
        handler.post {
            cropNx = sx.coerceIn(0.1f, 1f)
            cropNy = sy.coerceIn(0.1f, 1f)
        }
    }

    /**
     * Starts sending frames to [surface]. [ptsNs] gives each frame's timestamp (read when the
     * frame is captured) and frames captured while [paused] is true are skipped.
     */
    fun startRecording(
        surface: Surface, w: Int, h: Int, rotation: Int,
        ptsNs: () -> Long, paused: () -> Boolean,
    ) {
        handler.post {
            if (rotation != deviceRotation) {
                presentPendingUpTo(Long.MAX_VALUE)
                deviceRotation = rotation
                invalidateMask()
            }
            rotationLocked = true
            encoderSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            encoderW = w; encoderH = h
            recordPts = ptsNs
            recordPaused = paused
        }
    }

    /** [done] runs on the GL thread once the encoder has received its last frame. */
    fun stopRecording(done: () -> Unit) {
        handler.post {
            presentPendingUpTo(Long.MAX_VALUE) // flush frames still waiting for their mask
            if (encoderSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrentAny()
                EGL14.eglDestroySurface(display, encoderSurface)
                encoderSurface = EGL14.EGL_NO_SURFACE
            }
            rotationLocked = false
            done()
        }
    }

    /**
     * Receives an upright copy of the finished picture up to 20 times a second, for the
     * remote viewer. Called on the GL thread; the bitmap is reused, so copy or compress at once.
     */
    @Volatile var snapshotListener: ((Bitmap) -> Unit)? = null
    /** Snapshots are only made while this says someone is watching. */
    @Volatile var snapshotWanted: () -> Boolean = { false }
    /** Longest side of the remote picture, in pixels. */
    @Volatile var snapshotSize = 960
    /** Shortest time between remote pictures (50 ms = 20 per second). */
    @Volatile var snapshotIntervalMs = 50L
    private var snapFbo = 0
    private var snapTex = 0
    private var snapW = 0
    private var snapH = 0
    private var snapBitmap: Bitmap? = null
    private var snapBuf: ByteBuffer? = null
    private var lastSnapMs = 0L

    private fun maybeSnapshot(slot: Slot) {
        val listener = snapshotListener ?: return
        if (!snapshotWanted()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSnapMs < snapshotIntervalMs) return
        lastSnapMs = now
        val (ww, wh) = worldSize()
        val (cx, cy) = cropW()
        if (ww == 0 || wh == 0) return
        val vw = ww * cx; val vh = wh * cy
        val s = snapshotSize.toFloat() / max(vw, vh)
        val w = ((vw * s).roundToInt() / 2) * 2
        val h = ((vh * s).roundToInt() / 2) * 2
        if (w != snapW || h != snapH) {
            if (snapTex == 0) {
                val t = IntArray(1); GLES30.glGenTextures(1, t, 0); snapTex = t[0]
                val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); snapFbo = f[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, snapTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            setLinear(GLES30.GL_TEXTURE_2D)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, snapTex, 0)
            snapW = w; snapH = h
            snapBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            snapBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        makeCurrentAny()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapFbo)
        // Same view as the recording, flipped so rows come out top first.
        render(snapW, snapH, mul(scaleC(cx, cy), flipYC()), mode, slot.tex)
        val buf = snapBuf!!
        buf.clear()
        GLES30.glReadPixels(0, 0, snapW, snapH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        buf.rewind()
        snapBitmap!!.copyPixelsFromBuffer(buf)
        runCatching { listener(snapBitmap!!) }
    }

    /** Converts a tap on the preview (0..1, y down) into camera buffer coordinates (0..1, y down). */
    fun previewToCameraPoint(x: Float, yDown: Float): FloatArray? {
        val m = focusMatrix ?: return null
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, m, 0, floatArrayOf(x, 1f - yDown, 0f, 1f), 0)
        return floatArrayOf(out[0].coerceIn(0f, 1f), out[1].coerceIn(0f, 1f))
    }

    fun release() {
        handler.post {
            stopBackdropVideo()
            destroyPreview()
            cameraSurface?.release(); cameraTexture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
        segExecutor.execute { segmenter?.close(); segmenter = null }
        segExecutor.shutdown()
    }

    // ================================================================== camera input

    private fun attachCamera(request: SurfaceRequest, front: Boolean, sensorDeg: Int) {
        presentPendingUpTo(Long.MAX_VALUE)
        cameraSurface?.release()
        cameraTexture?.release()
        isFront = front
        sensorRotation = sensorDeg
        bufW = request.resolution.width
        bufH = request.resolution.height
        geometryReady = false
        geometrySig = -1
        invalidateMask()

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(bufW, bufH)
        st.setOnFrameAvailableListener({ onCameraFrame() }, handler)
        val surface = Surface(st)
        cameraTexture = st
        cameraSurface = surface
        request.provideSurface(surface, glExecutor) {
            if (cameraSurface === surface) {
                surface.release(); st.release()
                cameraSurface = null; cameraTexture = null
            }
        }
    }

    /**
     * Works out, from the SurfaceTexture transform itself, whether the phone already rotated and
     * mirrored the camera image. This differs between phones, so we detect instead of assuming.
     */
    private var geometrySig = -1
    private var sigChangeFrames = 0

    /** Which way the camera transform turns and mirrors the picture, as a small number. */
    private fun geometrySignature(): Int {
        val a00 = stMatrix[0]; val a10 = stMatrix[1]
        val a01 = stMatrix[4]; val a11 = stMatrix[5]
        val swaps = abs(a00) < abs(a10)
        val mirrored = (a00 * a11 - a01 * a10) > 0f
        return (if (swaps) 1 else 0) or (if (mirrored) 2 else 0)
    }

    private fun computeGeometry() {
        val a00 = stMatrix[0]; val a10 = stMatrix[1]
        val a01 = stMatrix[4]; val a11 = stMatrix[5]
        val swapsAxes = abs(a00) < abs(a10)
        // A plain (unmirrored) SurfaceTexture transform always contains one vertical flip, so its
        // determinant is negative. Positive means the producer mirrored the image.
        val mirrored = (a00 * a11 - a01 * a10) > 0f
        val extraRotation = if (!swapsAxes && sensorRotation % 180 != 0) sensorRotation else 0

        // Turning the phone must turn the picture like a window (back camera) or a mirror (front
        // camera). Both behave the same way; an un-mirrored front image would turn the wrong way.
        // So bring every camera to that "window" form here, and apply the user's mirror choice
        // later in world space.
        val needsFlip = if (isFront) !mirrored else mirrored
        var fix = rotC(extraRotation)
        if (needsFlip) fix = mul(fix, flipXC())
        fixMatrix = fix

        val portraitSwap = swapsAxes || extraRotation % 180 != 0
        val nw = if (portraitSwap) bufH else bufW
        val nh = if (portraitSwap) bufW else bufH
        naturalWidth = nw; naturalHeight = nh
        geometryReady = true
        Log.i(TAG, "camera ${bufW}x$bufH front=$isFront swaps=$swapsAxes mirrored=$mirrored extra=$extraRotation -> natural ${nw}x$nh")
        ContextCompat.getMainExecutor(appContext).execute { onGeometry?.invoke(nw, nh) }
    }

    // ================================================================== per camera frame

    private fun onCameraFrame() {
        val t0 = SystemClock.uptimeMillis()
        try {
            handleCameraFrame()
        } finally {
            noteGlBusy(SystemClock.uptimeMillis() - t0)
        }
    }

    // How much of the time the drawing thread is busy. On phones with a slow graphics chip it
    // is the drawing (not the cut-out model) that falls behind, so both are watched.
    private var glBusyMs = 0L
    private var glWindowStart = 0L
    private var glSlowWindows = 0

    private fun noteGlBusy(ms: Long) {
        val now = SystemClock.uptimeMillis()
        if (glWindowStart == 0L) glWindowStart = now
        glBusyMs += ms
        val span = now - glWindowStart
        if (span < 3000) return
        val busy = glBusyMs.toFloat() / span
        glBusyMs = 0
        glWindowStart = now
        glSlowWindows = if (busy > 0.8f) glSlowWindows + 1 else 0
        if (glSlowWindows >= 2 && !slowReported) {
            slowReported = true
            glSlowWindows = 0
            ContextCompat.getMainExecutor(appContext).execute { onTooSlow?.invoke() }
        }
    }

    private fun handleCameraFrame() {
        val st = cameraTexture ?: return
        makeCurrentAny()
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        st.getTransformMatrix(stMatrix)
        // The first frames after the camera (re)starts can carry a temporary transform, and the
        // real one arrives a moment later. Re-check every frame and redo the geometry when the
        // rotation or mirroring changes, so the picture never stays sideways.
        val sig = geometrySignature()
        // A changed transform must last a few frames before it counts, so a single odd frame
        // can never make the picture (and the cut-out) restart over and over.
        sigChangeFrames = if (geometryReady && sig != geometrySig) sigChangeFrames + 1 else 0
        if (!geometryReady || sigChangeFrames >= 3) {
            sigChangeFrames = 0
            if (geometryReady) {
                presentPendingUpTo(Long.MAX_VALUE)
                invalidateMask()
            }
            geometrySig = sig
            computeGeometry()
        }

        // Front camera is a mirror view at this point; un-mirror it if the user turned mirroring off.
        val personMirror = if (isFront && !mirrorFront) flipXC() else identity()
        nFromW = mul(rotC(deviceRotation), personMirror)
        val camTexFromN = mul(stMatrix.copyOf(), fixMatrix)

        ensureRing()
        ensureSmallTarget()
        if (ring.isEmpty()) return

        // 1. Copy the frame into the ring (a slot still waiting is shown first, with the latest mask).
        val slot = ring[(frameCounter % ring.size).toInt()]
        if (slot.pending) presentPendingUpTo(slot.id)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, slot.fbo)
        GLES30.glViewport(0, 0, ringW, ringH)
        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(copyLoc("uCam"), 0)
        GLES30.glUniformMatrix4fv(copyLoc("uTexM"), 1, false, camTexFromN, 0)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // Mipmaps let the small crops (segmentation, blur) be made without aliasing.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.tex)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        val now = SystemClock.uptimeMillis()
        slot.id = ++frameCounter
        slot.pending = true
        slot.capturedMs = now
        slot.encode = encoderSurface != EGL14.EGL_NO_SURFACE && !recordPaused()
        slot.ptsNs = if (slot.encode) recordPts() else 0L

        // 2. Segment it, or show it straight away when no cut-out is needed.
        val wanted = (mode != MODE_NONE || framingWanted) && segmenter != null
        if (!wanted) {
            presentPendingUpTo(slot.id)
            return
        }
        if (segBusy.compareAndSet(false, true)) submitSegmentation(slot)
        // Safety net: never hold a frame for long if the model is slow or failing.
        val stale = ring.filter { it.pending && now - it.capturedMs > MAX_WAIT_MS }.maxOfOrNull { it.id }
        if (stale != null) presentPendingUpTo(stale)
    }

    /** Cuts the square crop around the person out of [slot] and sends it to the model. */
    private fun submitSegmentation(slot: Slot) {
        val (ww, wh) = worldSize()
        val crop = nextRoi
        val whole = crop == null || !fullReady || segCount++ % 4 == 0L
        val roi = if (whole) fullRoi(ww, wh) else crop!!
        // Smoothing only makes sense between masks of the same region; the whole picture and the
        // crop each keep their own history.
        val roiChanged = if (whole) lastFullRoi?.contentEquals(roi) != true else lastRoi?.contentEquals(roi) != true
        if (whole) lastFullRoi = roi else lastRoi = roi

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, roiFbo)
        // Output row 0 = top of the crop, so the read-back Bitmap is upright.
        val wFromOut = identity().also {
            Matrix.translateM(it, 0, roi[0], roi[3], 0f)
            Matrix.scaleM(it, 0, roi[2] - roi[0], -(roi[3] - roi[1]), 1f)
        }
        render(ROI_SIZE, ROI_SIZE, wFromOut, MODE_RAW, slot.tex)
        readBuf.clear()
        GLES30.glReadPixels(0, 0, ROI_SIZE, ROI_SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readBuf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val bmp = segBitmap ?: Bitmap.createBitmap(ROI_SIZE, ROI_SIZE, Bitmap.Config.ARGB_8888).also { segBitmap = it }
        readBuf.rewind()
        bmp.copyPixelsFromBuffer(readBuf)

        val id = slot.id
        val gen = maskGen
        val mirroredView = isFront && mirrorFront
        try {
            segExecutor.execute {
                var mask: PersonSegmenter.Mask? = null
                try {
                    val t0 = SystemClock.uptimeMillis()
                    mask = segmenter?.segment(bmp, resetSmoothing = roiChanged, stream = if (whole) 0 else 1)
                    noteSegmentationTime(SystemClock.uptimeMillis() - t0)
                    if (mask != null) {
                        // A whole-picture pass decides the crop afresh (it may have drifted);
                        // a crop pass keeps it unless the person nears its edge.
                        nextRoi = chooseNextRoi(mask, roi, ww, wh, whole)
                        if (whole) reportFraming(mask, bmp, roi, mirroredView)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "segmentation failed", t)
                } finally {
                    segBusy.set(false)
                    val m = mask
                    handler.post {
                        val t0 = SystemClock.uptimeMillis()
                        if (m != null && gen == maskGen) uploadMask(m, roi, whole)
                        presentPendingUpTo(id) // this frame (and any skipped before it) can now be shown
                        noteGlBusy(SystemClock.uptimeMillis() - t0)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            segBusy.set(false)
        }
    }

    /**
     * Called on the main thread when the phone cannot keep up (the cut-out takes longer than
     * about 90 ms a frame on average), so the app can switch to lighter settings.
     */
    var onTooSlow: (() -> Unit)? = null
    private var segTimeAvg = 0f
    private var segSamples = 0
    @Volatile private var slowReported = false

    /** Runs on the segmentation thread. */
    private fun noteSegmentationTime(ms: Long) {
        segSamples++
        segTimeAvg = if (segSamples == 1) ms.toFloat() else segTimeAvg * 0.9f + ms * 0.1f
        if (segSamples > 30 && segTimeAvg > 90f && !slowReported) {
            slowReported = true
            ContextCompat.getMainExecutor(appContext).execute { onTooSlow?.invoke() }
        }
    }

    /** Start measuring speed afresh (after the settings changed). */
    fun resetSpeedCheck() {
        segExecutor.execute { segSamples = 0; segTimeAvg = 0f; slowReported = false }
        handler.post { glBusyMs = 0; glWindowStart = 0; glSlowWindows = 0 }
    }

    /** A square crop (in pixels) covering the whole picture, letterboxed. */
    private fun fullRoi(ww: Int, wh: Int): FloatArray {
        val side = max(ww, wh).toFloat()
        val hx = side / 2f / ww
        val hy = side / 2f / wh
        return floatArrayOf(0.5f - hx, 0.5f - hy, 0.5f + hx, 0.5f + hy)
    }

    /**
     * Picks the next crop from where the person is in [mask] (which covers [roi]). The crop only
     * changes when the person nears its edge or becomes much smaller, so it is stable from frame
     * to frame. A tight crop shows the model the person in much more detail.
     */
    private fun chooseNextRoi(
        mask: PersonSegmenter.Mask, roi: FloatArray, ww: Int, wh: Int, whole: Boolean,
    ): FloatArray? {
        val n = mask.width
        var minX = n; var maxX = -1; var minY = n; var maxY = -1; var count = 0
        var y = 0
        while (y < n) {
            var x = 0
            while (x < n) {
                if ((mask.data[y * n + x].toInt() and 0xFF) > 128) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        if (count < (n / 2) * (n / 2) / 200) return if (whole) null else nextRoi // nobody there
        val rw = roi[2] - roi[0]
        val rh = roi[3] - roi[1]
        // Person box in world coordinates (mask rows run top to bottom).
        val bx0 = roi[0] + minX.toFloat() / n * rw
        val bx1 = roi[0] + (maxX + 2f) / n * rw
        val by1 = roi[3] - minY.toFloat() / n * rh
        val by0 = roi[3] - (maxY + 2f) / n * rh
        // If the person touches the crop edge they may continue beyond it: allow more room.
        val touches = !whole && (minX <= 2 || minY <= 2 || maxX >= n - 4 || maxY >= n - 4)
        val margin = if (touches) 2.0f else 1.6f
        val sidePx = (max((bx1 - bx0) * ww, (by1 - by0) * wh) * margin)
            .coerceIn(min(ww, wh) * 0.35f, max(ww, wh).toFloat())
        val cx = (bx0 + bx1) / 2f
        val cy = (by0 + by1) / 2f
        val hx = sidePx / 2f / ww
        val hy = sidePx / 2f / wh
        val target = floatArrayOf(cx - hx, cy - hy, cx + hx, cy + hy)

        // Keep the current crop while it still fits well (avoids jitter and lets smoothing work).
        val pad = 0.06f
        if (whole) {
            // Keep the current crop if it still contains the person comfortably.
            val cur = nextRoi ?: return target
            val cw = cur[2] - cur[0]; val ch = cur[3] - cur[1]
            val ok = bx0 >= cur[0] + pad * cw && bx1 <= cur[2] - pad * cw &&
                by0 >= cur[1] + pad * ch && by1 <= cur[3] - pad * ch && cw * ww <= sidePx * 1.6f
            return if (ok) cur else target
        }
        val fits = bx0 >= roi[0] + pad * rw && bx1 <= roi[2] - pad * rw &&
            by0 >= roi[1] + pad * rh && by1 <= roi[3] - pad * rh &&
            !touches && rw * ww <= sidePx * 1.5f
        return if (fits) roi else target
    }

    /** Runs on the segmentation thread. Converts the crop's mask into whole-picture framing. */
    private fun reportFraming(mask: PersonSegmenter.Mask, frame: Bitmap, roi: FloatArray, mirrored: Boolean) {
        val cb = onFraming ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastFramingMs < 150) return
        lastFramingMs = now
        val n = mask.width
        val pixels = IntArray(n * n).also { frame.getPixels(it, 0, n, 0, 0, n, n) }
        var sr = 0L; var sg = 0L; var sb = 0L
        var count = 0
        var sumX = 0.0
        var minY = n; var maxY = -1
        var y = 0
        while (y < n) {
            var x = 0
            while (x < n) {
                val i = y * n + x
                if ((mask.data[i].toInt() and 0xFF) > 140) {
                    count++
                    sumX += x
                    val c = pixels[i]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        val rw = roi[2] - roi[0]
        val rh = roi[3] - roi[1]
        val f = if (count == 0) {
            Framing(0f, 0.5f, 0f, 0f, mirrored)
        } else {
            val samples = ((n + 1) / 2) * ((n + 1) / 2)
            Framing(
                coverage = count.toFloat() / samples * rw * rh,
                centerX = (roi[0] + (sumX / count).toFloat() / n * rw).coerceIn(0f, 1f),
                top = (1f - (roi[3] - minY.toFloat() / n * rh)).coerceIn(0f, 1f),
                bottom = (1f - (roi[3] - (maxY + 1f) / n * rh)).coerceIn(0f, 1f),
                mirrored = mirrored,
                meanR = sr / (255f * count), meanG = sg / (255f * count), meanB = sb / (255f * count),
            )
        }
        ContextCompat.getMainExecutor(appContext).execute { cb(f) }
    }

    // ================================================================== showing / recording frames

    /** Shows (and records) every waiting frame up to [id], oldest first, with the current mask. */
    private fun presentPendingUpTo(id: Long) {
        while (true) {
            val next = ring.filter { it.pending && it.id <= id }.minByOrNull { it.id } ?: return
            present(next)
        }
    }

    private fun present(slot: Slot) {
        slot.pending = false
        makeCurrentAny()
        maybeSnapshot(slot)
        makeCurrentAny()
        if (mode == MODE_BLUR || (mode == MODE_IMAGE && !bgReady)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, smallFbo)
            // Flipped so the blur texture's rows run top to bottom, like the mask.
            render(smallW, smallH, flipYC(), MODE_RAW, slot.tex)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            val wFromOut = mul(rotC(-deviceRotation), scaleC(cropNx, cropNy))
            focusMatrix = mul(mul(mul(stMatrix.copyOf(), fixMatrix), nFromW), wFromOut)
            EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext)
            render(previewW, previewH, wFromOut, mode, slot.tex)
            EGL14.eglSwapBuffers(display, previewSurface)
        }
        if (slot.encode && encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, eglContext)
            val (cx, cy) = cropW()
            render(encoderW, encoderH, scaleC(cx, cy), mode, slot.tex)
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, slot.ptsNs)
            EGL14.eglSwapBuffers(display, encoderSurface)
        }
        makeCurrentAny()
    }

    /** The crop expressed in world axes (swapped when the phone is sideways). */
    private fun cropW(): Pair<Float, Float> =
        if (deviceRotation % 180 == 0) cropNx to cropNy else cropNy to cropNx

    private fun worldSize(): Pair<Int, Int> =
        if (deviceRotation % 180 == 0) naturalWidth to naturalHeight else naturalHeight to naturalWidth

    /** The ring holds a few upright full-resolution frames (with mipmaps) while masks are computed. */
    private fun ensureRing() {
        val w = naturalWidth
        val h = naturalHeight
        if (w == 0 || h == 0 || (w == ringW && h == ringH && ring.isNotEmpty())) return
        for (s in ring) {
            GLES30.glDeleteTextures(1, intArrayOf(s.tex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(s.fbo), 0)
        }
        ring.clear()
        ringW = w; ringH = h
        val levels = 1 + floor(ln(max(w, h).toDouble()) / ln(2.0)).toInt()
        repeat(RING_SIZE) {
            val t = IntArray(1); val f = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
            ring += Slot(t[0], f[0])
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun ensureSmallTarget() {
        val (ww, wh) = worldSize()
        if (ww == 0 || wh == 0) return
        val scale = 288f / min(ww, wh)
        val sw = ((ww * scale).roundToInt() / 2) * 2
        val sh = ((wh * scale).roundToInt() / 2) * 2
        if (sw == smallW && sh == smallH) return
        smallW = sw; smallH = sh
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smallTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, sw, sh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        setLinear(GLES30.GL_TEXTURE_2D)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, smallFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, smallTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun uploadMask(mask: PersonSegmenter.Mask, roi: FloatArray, whole: Boolean) {
        makeCurrentAny()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (whole) maskFullTex else maskTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mask.width, mask.height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask.data)
        )
        if (whole) {
            roi.copyInto(fullRect)
            fullReady = true
            if (nextRoi == null) roiReady = false // nobody to zoom in on
        } else {
            roi.copyInto(roiRect)
            roiReady = true
        }
    }

    private fun invalidateMask() {
        fullReady = false
        roiReady = false
        maskGen++
        nextRoi = null
        lastRoi = null
        lastFullRoi = null
        segmenter?.reset()
    }

    private fun render(w: Int, h: Int, wFromOut: FloatArray, renderMode: Int, frameTex: Int) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        // Never bind the texture we are rendering into.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (renderMode == MODE_RAW) bgTex else smallTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logoTex)
        GLES30.glUniform1i(loc("uLogo"), 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskFullTex)
        GLES30.glUniform1i(loc("uMaskFull"), 5)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, overlayTex)
        GLES30.glUniform1i(loc("uOverlay"), 6)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE7)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgVideoTex)
        GLES30.glUniform1i(loc("uBgVideo"), 7)
        GLES30.glUniform1f(loc("uBgIsVideo"), if (bgIsVideo) 1f else 0f)
        GLES30.glUniformMatrix4fv(loc("uBgVideoM"), 1, false, bgVideoMatrix, 0)
        GLES30.glUniform1f(loc("uOverlayOn"), if (overlayOn && renderMode != MODE_RAW) 1f else 0f)

        GLES30.glUniform1i(loc("uFrame"), 0)
        GLES30.glUniform1i(loc("uMask"), 1)
        GLES30.glUniform1i(loc("uSmall"), 2)
        GLES30.glUniform1i(loc("uBg"), 3)
        GLES30.glUniformMatrix4fv(loc("uNFromW"), 1, false, nFromW, 0)
        GLES30.glUniformMatrix4fv(loc("uWFromOut"), 1, false, wFromOut, 0)

        val effective = if (renderMode == MODE_IMAGE && !bgReady) MODE_BLUR else renderMode
        GLES30.glUniform1f(loc("uMode"), effective.toFloat())
        GLES30.glUniform1f(loc("uHasMask"), if (fullReady) 1f else 0f)
        GLES30.glUniform1f(loc("uHasRoi"), if (roiReady && fullReady) 1f else 0f)
        GLES30.glUniform1f(loc("uRefine"), if (refine) 1f else 0f)
        GLES30.glUniform2f(loc("uEdge"), edgeLo, edgeHi)
        GLES30.glUniform4f(loc("uMaskRect"), roiRect[0], roiRect[1], roiRect[2], roiRect[3])
        GLES30.glUniform4f(loc("uFullRect"), fullRect[0], fullRect[1], fullRect[2], fullRect[3])
        val texelRect = if (roiReady) roiRect else fullRect
        GLES30.glUniform2f(
            loc("uMaskTexel"),
            (texelRect[2] - texelRect[0]) / ROI_SIZE, (texelRect[3] - texelRect[1]) / ROI_SIZE
        )
        GLES30.glUniform2f(
            loc("uSmallTexel"),
            if (smallW > 0) 1f / smallW else 0f,
            if (smallH > 0) 1f / smallH else 0f
        )

        val (ww, wh) = worldSize()
        val (cx, cy) = cropW()
        GLES30.glUniform2f(loc("uCrop"), cx, cy)
        // Size / position only apply over a backdrop image; with blur the real you is in the background.
        val movable = effective == MODE_IMAGE
        GLES30.glUniform1f(loc("uPersonScale"), if (movable) personScale else 1f)
        GLES30.glUniform2f(loc("uPersonAnchor"), 0.5f, 0.5f - cy / 2f)
        GLES30.glUniform2f(loc("uPersonOffset"), if (movable) personOffX * cx else 0f, if (movable) personOffY * cy else 0f)
        // When the person is shrunk or moved, the edges of the camera picture become visible over
        // the backdrop; fade them out instead of showing a hard straight line (e.g. through hair).
        val transformed = movable && (personScale < 0.995f || personOffX != 0f || personOffY != 0f)
        GLES30.glUniform2f(
            loc("uFeather"),
            if (transformed) 0.05f else 0.0001f,
            if (transformed && personOffY > 0.001f) 0.05f else 0.0001f
        )
        GLES30.glUniform1f(loc("uGain"), gain)
        GLES30.glUniform1f(loc("uWarm"), warmth)
        val worldAspect = if (wh > 0) ww.toFloat() * cx / (wh * cy) else 1f
        var sx = 1f; var sy = 1f
        if (bgAspect > worldAspect) sx = worldAspect / bgAspect else sy = bgAspect / worldAspect
        GLES30.glUniform2f(loc("uBgScale"), sx, sy)

        // Logo, placed in the visible picture so it lands where the user put it on screen.
        val showLogo = logoOn && logoReady && renderMode != MODE_RAW
        GLES30.glUniform1f(loc("uLogoOn"), if (showLogo) 1f else 0f)
        GLES30.glUniform1f(loc("uLogoBehind"), if (logo.behind && effective != MODE_NONE) 1f else 0f)
        GLES30.glUniform1f(loc("uVisAspect"), worldAspect)
        GLES30.glUniform2f(loc("uLogoCenter"), logo.x, logo.y)
        val halfW = logo.size * worldAspect / 2f // in units of the visible height
        GLES30.glUniform2f(loc("uLogoHalf"), halfW, halfW * logoAspect)
        val rad = Math.toRadians(logo.rotation.toDouble())
        GLES30.glUniform2f(loc("uLogoRot"), kotlin.math.cos(rad).toFloat(), kotlin.math.sin(rad).toFloat())
        GLES30.glUniform1f(loc("uLogoAlpha"), logo.opacity)
        drawQuad()
    }

    private fun drawQuad() {
        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loc(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }
    private fun copyLoc(name: String) = copyLocs.getOrPut(name) { GLES30.glGetUniformLocation(copyProgram, name) }

    // ================================================================== setup

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        config = configs[0]
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeCurrentAny()

        copyProgram = buildProgram(COPY_VERTEX, COPY_FRAGMENT)
        program = buildProgram(VERTEX, FRAGMENT)
        val t = IntArray(8)
        GLES30.glGenTextures(8, t, 0)
        oesTex = t[0]; maskTex = t[1]; bgTex = t[2]; smallTex = t[3]; roiTex = t[4]; logoTex = t[5]; maskFullTex = t[6]
        overlayTex = t[7]
        val vt = IntArray(1)
        GLES30.glGenTextures(1, vt, 0)
        bgVideoTex = vt[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgVideoTex)
        setLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val f = IntArray(2)
        GLES30.glGenFramebuffers(2, f, 0)
        smallFbo = f[0]; roiFbo = f[1]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        setLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        for (tex in intArrayOf(maskTex, bgTex, smallTex, roiTex, logoTex, maskFullTex, overlayTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            setLinear(GLES30.GL_TEXTURE_2D)
        }
        // 1x1 placeholders so every sampler is always valid
        for (tex in intArrayOf(bgTex, maskTex, logoTex, maskFullTex, overlayTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(4)
            )
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, roiTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, ROI_SIZE, ROI_SIZE, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, roiFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, roiTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun makeCurrentAny() {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)
    }

    private fun setLinear(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun destroyPreview() {
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrentAny()
            EGL14.eglDestroySurface(display, previewSurface)
            previewSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader error: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link error: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "CompositeEngine"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        /** Frames kept while their masks are computed (enough for ~3 frames of model latency). */
        private const val RING_SIZE = 4
        /** Side of the square crop given to the model (it works at 256 internally). */
        private const val ROI_SIZE = 320
        /** Never hold a frame longer than this waiting for its mask. */
        private const val MAX_WAIT_MS = 250L

        const val MODE_NONE = 0   // plain camera, no background replacement
        const val MODE_BLUR = 1   // blurred real background
        const val MODE_IMAGE = 2  // backdrop image
        private const val MODE_RAW = 3

        private fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        private fun mul(a: FloatArray, b: FloatArray) = FloatArray(16).also { Matrix.multiplyMM(it, 0, a, 0, b, 0) }

        /** Rotation about the centre of the unit square, counter-clockwise in degrees. */
        private fun rotC(deg: Int) = identity().also {
            if (deg % 360 != 0) {
                Matrix.translateM(it, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(it, 0, deg.toFloat(), 0f, 0f, 1f)
                Matrix.translateM(it, 0, -0.5f, -0.5f, 0f)
            }
        }

        private fun scaleC(sx: Float, sy: Float) = identity().also {
            if (sx != 1f || sy != 1f) {
                Matrix.translateM(it, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(it, 0, sx, sy, 1f)
                Matrix.translateM(it, 0, -0.5f, -0.5f, 0f)
            }
        }

        private fun flipXC() = identity().also {
            Matrix.translateM(it, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(it, 0, -1f, 1f, 1f)
            Matrix.translateM(it, 0, -0.5f, -0.5f, 0f)
        }

        private fun flipYC() = identity().also {
            Matrix.translateM(it, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(it, 0, 1f, -1f, 1f)
            Matrix.translateM(it, 0, -0.5f, -0.5f, 0f)
        }

        private const val COPY_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uTexM;
out vec2 vT;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vT = (uTexM * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        private const val COPY_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vT;
uniform samplerExternalOES uCam;
out vec4 outColor;
void main() { outColor = vec4(texture(uCam, vT).rgb, 1.0); }
"""

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uWFromOut;
out vec2 vW;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vW = (uWFromOut * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vW;
uniform sampler2D uFrame;   // upright camera frame (natural space), with mipmaps
uniform sampler2D uMask;    // detailed person mask of the crop uMaskRect, rows top to bottom
uniform sampler2D uMaskFull; // person mask of the whole picture (uFullRect)
uniform vec4 uFullRect;
uniform float uHasRoi;
uniform sampler2D uSmall;   // small copy of the picture, for the blur effect
uniform sampler2D uBg;
uniform samplerExternalOES uBgVideo;
uniform mat4 uBgVideoM;
uniform float uBgIsVideo;
uniform mat4 uNFromW;
uniform float uMode;
uniform float uHasMask;
uniform float uRefine;
uniform vec2 uEdge;
uniform vec4 uMaskRect;
uniform vec2 uMaskTexel;
uniform vec2 uSmallTexel;
uniform vec2 uBgScale;
uniform vec2 uCrop;
uniform float uPersonScale;
uniform vec2 uPersonAnchor;
uniform vec2 uPersonOffset;
uniform vec2 uFeather; // x: fade at top and sides, y: fade at the bottom
uniform float uGain;
uniform float uWarm;
uniform sampler2D uLogo;    // premultiplied alpha
uniform float uLogoOn;
uniform float uLogoBehind;
uniform float uVisAspect;
uniform vec2 uLogoCenter;
uniform vec2 uLogoHalf;
uniform vec2 uLogoRot;      // cos, sin
uniform float uLogoAlpha;
uniform sampler2D uOverlay;  // premultiplied, covers the visible picture
uniform float uOverlayOn;
out vec4 outColor;

vec3 withOverlay(vec3 c) {
    if (uOverlayOn < 0.5) return c;
    vec2 v = (vW - 0.5) / uCrop + 0.5;
    if (v.x < 0.0 || v.x > 1.0 || v.y < 0.0 || v.y > 1.0) return c;
    vec4 o = texture(uOverlay, vec2(v.x, 1.0 - v.y));
    return c * (1.0 - o.a) + o.rgb;
}

// Draws the logo over colour c at this pixel.
vec3 withLogo(vec3 c) {
    if (uLogoOn < 0.5) return c;
    vec2 v = (vW - 0.5) / uCrop + 0.5;                        // visible picture, 0..1
    vec2 d = vec2((v.x - uLogoCenter.x) * uVisAspect, v.y - uLogoCenter.y);
    d = vec2(uLogoRot.x * d.x + uLogoRot.y * d.y, -uLogoRot.y * d.x + uLogoRot.x * d.y);
    vec2 t = vec2(d.x / uLogoHalf.x, -d.y / uLogoHalf.y) * 0.5 + 0.5;
    if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) return c;
    vec4 l = texture(uLogo, t) * uLogoAlpha;
    return c * (1.0 - l.a) + l.rgb;
}

vec3 camAt(vec2 w) { return texture(uFrame, (uNFromW * vec4(w, 0.0, 1.0)).xy).rgb; }
vec2 img(vec2 w) { return vec2(w.x, 1.0 - w.y); }

// Person probability at world point w: the detailed crop mask inside the crop, fading into
// the whole-picture mask towards the crop's border, so the crop can never cut the person.
float maskAt(vec2 w) {
    vec2 f = (w - uFullRect.xy) / (uFullRect.zw - uFullRect.xy);
    float full = texture(uMaskFull, vec2(f.x, 1.0 - f.y)).r;
    if (uHasRoi < 0.5) return full;
    vec2 m = (w - uMaskRect.xy) / (uMaskRect.zw - uMaskRect.xy);
    float edge = min(min(m.x, 1.0 - m.x), min(m.y, 1.0 - m.y));
    if (edge <= 0.0) return full;
    float roi = texture(uMask, vec2(m.x, 1.0 - m.y)).r;
    return mix(full, roi, smoothstep(0.0, 0.08, edge));
}

vec3 adjust(vec3 c) {
    c *= uGain;
    c.r *= 1.0 + 0.12 * uWarm;
    c.b *= 1.0 - 0.12 * uWarm;
    return clamp(c, 0.0, 1.0);
}

void main() {
    if (uMode > 2.5) {
        // Raw copy (crops for the model, blur source): black outside the picture.
        bool outside = vW.x < 0.0 || vW.x > 1.0 || vW.y < 0.0 || vW.y > 1.0;
        outColor = outside ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(camAt(vW), 1.0);
        return;
    }
    if (uMode < 0.5) { outColor = vec4(withOverlay(withLogo(adjust(camAt(vW)))), 1.0); return; }

    // Where this output pixel falls in the (possibly shrunk and moved) person layer.
    vec2 p = (vW - uPersonAnchor - uPersonOffset) / uPersonScale + uPersonAnchor;
    vec3 cam = camAt(p);
    float inside = smoothstep(0.0, uFeather.x, p.x) * smoothstep(0.0, uFeather.x, 1.0 - p.x)
                 * smoothstep(0.0, uFeather.x, 1.0 - p.y) * smoothstep(0.0, uFeather.y, p.y);

    float m = 1.0;
    // Is this pixel near the edge of the person? Five cheap taps decide. Far inside you or far
    // out in the background the expensive edge refinement below would not change anything, so
    // it only runs in the thin band around your outline (most of the picture skips it).
    bool nearEdge = false;
    float m0 = 1.0;
    if (uHasMask > 0.5) {
        m0 = maskAt(p);
        vec2 r = uMaskTexel * 1.8;
        float lo = min(m0, min(min(maskAt(p + vec2(r.x, r.y)), maskAt(p - vec2(r.x, r.y))),
                               min(maskAt(p + vec2(r.x, -r.y)), maskAt(p - vec2(r.x, -r.y)))));
        float hi = max(m0, max(max(maskAt(p + vec2(r.x, r.y)), maskAt(p - vec2(r.x, r.y))),
                               max(maskAt(p + vec2(r.x, -r.y)), maskAt(p - vec2(r.x, -r.y)))));
        nearEdge = lo < 0.97 && hi > 0.03;
    }
    if (uHasMask > 0.5) {
        if (uRefine > 0.5 && nearEdge) {
            // Edge refinement at full resolution:
            // 1) joint bilateral: the coarse mask follows colour edges;
            // 2) colour-line matting: near an edge, estimate the local person colour F and
            //    background colour B and place this pixel between them. This removes the halo
            //    of real background around shoulders and hair when the colours differ.
            vec3 sf = vec3(0.0);
            vec3 sb = vec3(0.0);
            float wf = 0.0;
            float wb = 0.0;
            float ms = 0.0;
            float mw = 0.0;
            for (int i = -2; i <= 2; i++) {
                for (int j = -2; j <= 2; j++) {
                    vec2 w = p + vec2(float(i), float(j)) * uMaskTexel * 0.9;
                    vec3 c = camAt(w);
                    float mk = maskAt(w);
                    vec3 d = c - cam;
                    float wt = exp(-dot(d, d) * 40.0);
                    ms += mk * wt;
                    mw += wt;
                    float fk = smoothstep(0.6, 0.95, mk);
                    float bk = 1.0 - smoothstep(0.05, 0.4, mk);
                    sf += c * fk; wf += fk;
                    sb += c * bk; wb += bk;
                }
            }
            float mBil = ms / mw;
            vec3 F = sf / max(wf, 0.001);
            vec3 B = sb / max(wb, 0.001);
            vec3 fb = F - B;
            float len2 = dot(fb, fb);
            float aCol = clamp(dot(cam - B, fb) / max(len2, 0.0001), 0.0, 1.0);
            // Only trust the colours where you and the background clearly differ; on weak contrast
            // camera noise would make the edge shimmer from frame to frame.
            float conf = step(0.5, wf) * step(0.5, wb) * smoothstep(0.02, 0.08, len2);
            m = mix(mBil, aCol, conf * 0.85);
        } else {
            m = m0;
        }
    }
    m = smoothstep(uEdge.x, uEdge.y, m) * inside;

    vec3 bg = vec3(0.0);
    if (m > 0.998) {
        // Fully covered by you: the background is not visible here, skip it.
    } else if (uMode < 1.5) {
        // Blur only the background: taps on the person are left out, so dark hair or clothes
        // are not smeared into the blurred room as a shadow around you.
        vec2 t = img(vW);
        vec3 acc = vec3(0.0);
        vec3 all = vec3(0.0);
        float ws = 0.0;
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                vec2 tt = t + vec2(float(i), float(j)) * uSmallTexel * 2.2;
                vec3 col = texture(uSmall, tt).rgb;
                float wb = 1.0 - (uHasMask > 0.5 ? maskAt(vec2(tt.x, 1.0 - tt.y)) : 0.0);
                wb *= wb;
                acc += col * wb;
                ws += wb;
                all += col;
            }
        }
        bg = ws > 0.05 ? acc / ws : all / 49.0;
    } else {
        vec2 visible = (vW - 0.5) / uCrop + 0.5; // backdrop fills the visible (cropped) area
        vec2 bt = (img(visible) - 0.5) * uBgScale + 0.5;  // rows top to bottom
        if (uBgIsVideo > 0.5) {
            bg = texture(uBgVideo, (uBgVideoM * vec4(bt.x, 1.0 - bt.y, 0.0, 1.0)).xy).rgb;
        } else {
            bg = texture(uBg, bt).rgb;
        }
    }
    vec3 c;
    if (uLogoBehind > 0.5) {
        c = mix(withLogo(bg), adjust(cam), m);   // logo on the wall, behind you
    } else {
        c = withLogo(mix(bg, adjust(cam), m));   // logo on top, like a TV logo
    }
    outColor = vec4(withOverlay(c), 1.0);
}
"""
    }
}
