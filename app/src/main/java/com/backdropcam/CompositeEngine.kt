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
    private var bgTex = 0
    private var smallTex = 0     // small copy of the whole picture, source of the blur effect
    private var smallFbo = 0
    private var smallW = 0
    private var smallH = 0
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
        /** Sent to the model (false = waits for the cut-out of the next frame that is). */
        var submitted = false
    }

    /** Weak phones: the cut-out is worked out at a lower resolution, so they can keep up. */
    @Volatile var lightMode = false


    // Steadied cut-out (alpha), ping-ponged between two textures in world space.
    private val alphaTex = IntArray(2)
    private val alphaFbo = IntArray(2)
    private var alphaW = 0
    private var alphaH = 0
    private var alphaCur = 0
    private var alphaHasPrev = false
    private var alphaReady = false

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
    /** Second, light model for the whole-picture pass that runs next to the close-up pass. */
    private val segBusy = AtomicBoolean(false)
    @Volatile private var maskGen = 0
    // Two masks: one of the whole picture (always available, a little coarser) and one of the
    // crop around the person (detailed). The crop mask is used inside the crop and blends into
    // the whole-picture mask at its border, so a crop that is too tight can never cut you off.
    private var maskFullTex = 0
    private var fullReady = false
    private val fullRect = floatArrayOf(0f, 0f, 1f, 1f)
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
            // The screen may already be gone again (the app was left at that very moment).
            previewSurface = try {
                if (surface.isValid) EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                else EGL14.EGL_NO_SURFACE
            } catch (e: Exception) {
                EGL14.EGL_NO_SURFACE
            }
            if (previewSurface == EGL14.EGL_NO_SURFACE) return@post
            previewW = w; previewH = h
            // Frames can come in short bursts (when a mask arrives); never wait for the display.
            EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext)
            EGL14.eglSwapInterval(display, 0)
            makeCurrentAny()
        }
    }

    /**
     * Stops drawing to the screen. Waits (briefly) until the drawing thread has really let go
     * of it, because Android takes the screen surface away as soon as this returns; drawing to
     * it after that can crash the app.
     */
    fun clearPreviewSurface() {
        val done = java.util.concurrent.CountDownLatch(1)
        if (!handler.post { try { destroyPreview() } finally { done.countDown() } }) return
        runCatching { done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS) }
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
                dropPending()
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
                dropPending()
                deviceRotation = rotation
                invalidateMask()
            }
            rotationLocked = true
            encoderSurface = try {
                EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            } catch (e: Exception) {
                Log.e(TAG, "encoder surface failed", e)
                EGL14.EGL_NO_SURFACE
            }
            encoderW = w; encoderH = h
            recordPts = ptsNs
            recordPaused = paused
        }
    }

    /** [done] runs on the GL thread once the encoder has received its last frame. */
    fun stopRecording(done: () -> Unit) {
        handler.post {
            dropPending() // frames still waiting for their own cut-out are left out, never mismatched
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
        segExecutor.execute {
            segmenter?.close(); segmenter = null
        }
        segExecutor.shutdown()
    }

    // ================================================================== camera input

    private fun attachCamera(request: SurfaceRequest, front: Boolean, sensorDeg: Int) {
        dropPending()
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
        if (reads.isNotEmpty()) pollReads()
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
                dropPending()
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

        // 1. Copy the frame into a free place in the ring. If every place is taken, the oldest
        //    waiting frame is shown now (with the newest cut-out), so no frame is ever dropped.
        val slot = ring.firstOrNull { !it.pending }
            ?: ring.filter { it.pending && !it.submitted }.minByOrNull { it.id }?.also { presentPendingUpTo(it.id) }
            ?: return
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
        statCamFrames++
        slot.id = ++frameCounter
        slot.pending = true
        slot.capturedMs = now
        slot.encode = encoderSurface != EGL14.EGL_NO_SURFACE && !recordPaused()
        slot.ptsNs = if (slot.encode) recordPts() else 0L

        // 2. Show it straight away when no cut-out is needed.
        val wanted = (mode != MODE_NONE || framingWanted) && segmenter != null
        if (!wanted) {
            dropPending(except = slot)
            present(slot)
            return
        }
        // 3. Every frame that is shown or recorded gets a cut-out made from that very frame, so
        //    the cut-out never lags behind your movement. Two frames are worked on at once (one
        //    in the model, the next one already prepared), so the model never waits; only a
        //    frame arriving while both places are taken is left out.
        slot.submitted = inFlight.get() < 2 && submitSegmentation(slot)
        // Not sent: it is shown with the cut-out of the next frame that is (1/30 s later), so the
        // video keeps every frame and the cut-out is never more than a frame away.
        // Safety net: a model that hangs must not freeze the picture.
        ring.filter { it.pending && now - it.capturedMs > MAX_WAIT_MS }.forEach { present(it) }
    }

    /** Leaves a frame out (it never got its own cut-out). */
    private fun skip(slot: Slot) {
        slot.pending = false
    }

    private fun dropPending(except: Slot? = null) {
        ring.filter { it.pending && it !== except }.forEach { skip(it) }
    }

    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val bitmapPool = java.util.concurrent.ArrayBlockingQueue<Bitmap>(3)
    private var bitmapsMade = 0

    private fun takeBitmap(): Bitmap? = bitmapPool.poll() ?: if (bitmapsMade < 3) {
        bitmapsMade++
        Bitmap.createBitmap(ROI_SIZE, ROI_SIZE, Bitmap.Config.ARGB_8888)
    } else null

    // Picture for the model is read back WITHOUT waiting: the graphics chip copies it into a
    // buffer in the background (a "pixel buffer"), and the model gets it a moment later. Before,
    // the app stopped every frame until the graphics chip had finished all its work.
    private class PendingRead(val id: Long, val pbo: Int, val fence: Long, val roi: FloatArray, val changed: Boolean, val gen: Int)
    private val reads = ArrayDeque<PendingRead>()
    private val pbos = IntArray(3)
    private var pboNext = 0
    private var pollScheduled = false
    private val pollRunnable = Runnable { pollScheduled = false; pollReads() }

    private fun schedulePoll() {
        if (pollScheduled) return
        pollScheduled = true
        handler.postDelayed(pollRunnable, 3)
    }

    /**
     * Starts the cut-out of [slot] (one model pass over the whole picture). When it is done, the
     * frame is shown and recorded with the cut-out made from itself. Returns false if it could
     * not be started.
     */
    private fun submitSegmentation(slot: Slot): Boolean {
        if (inFlight.get() >= 2) return false
        val (ww, wh) = worldSize()
        if (ww == 0 || wh == 0) return false
        if (pbos[0] == 0) {
            GLES30.glGenBuffers(3, pbos, 0)
            for (p in pbos) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, p)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, ROI_SIZE * ROI_SIZE * 4, null, GLES30.GL_STREAM_READ)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }
        val roi = fullRoi(ww, wh)
        val changed = lastFullRoi?.contentEquals(roi) != true
        lastFullRoi = roi

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, roiFbo)
        val wFromOut = identity().also {
            Matrix.translateM(it, 0, roi[0], roi[3], 0f)
            Matrix.scaleM(it, 0, roi[2] - roi[0], -(roi[3] - roi[1]), 1f)
        }
        render(ROI_SIZE, ROI_SIZE, wFromOut, MODE_RAW, slot.tex)
        val pbo = pbos[pboNext]
        pboNext = (pboNext + 1) % pbos.size
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
        GLES30.glReadPixels(0, 0, ROI_SIZE, ROI_SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        reads.addLast(PendingRead(slot.id, pbo, fence, roi, changed, maskGen))
        inFlight.incrementAndGet()
        schedulePoll()
        return true
    }

    /** Hands every finished read-back to the model, oldest first, without ever waiting. */
    private fun pollReads() {
        while (reads.isNotEmpty()) {
            val r = reads.first()
            val status = GLES30.glClientWaitSync(r.fence, 0, 0)
            if (status == GLES30.GL_TIMEOUT_EXPIRED) {
                schedulePoll()
                return
            }
            reads.removeFirst()
            GLES30.glDeleteSync(r.fence)
            val bmp = takeBitmap() ?: Bitmap.createBitmap(ROI_SIZE, ROI_SIZE, Bitmap.Config.ARGB_8888)
            makeCurrentAny()
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, r.pbo)
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, ROI_SIZE * ROI_SIZE * 4, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            if (mapped != null) {
                mapped.order(ByteOrder.nativeOrder())
                bmp.copyPixelsFromBuffer(mapped)
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            if (mapped == null) {
                bitmapPool.offer(bmp)
                inFlight.decrementAndGet()
                presentPendingUpTo(r.id)
                continue
            }
            runModel(r, bmp)
        }
    }

    private fun runModel(r: PendingRead, bmp: Bitmap) {
        val (ww, wh) = worldSize()
        val mirroredView = isFront && mirrorFront
        try {
            segExecutor.execute {
                var mask: PersonSegmenter.Mask? = null
                try {
                    val t0 = SystemClock.uptimeMillis()
                    mask = segmenter?.segment(bmp, resetSmoothing = r.changed, stream = 0)
                    noteSegmentationTime(SystemClock.uptimeMillis() - t0)
                    if (mask != null && r.gen == maskGen) reportFraming(mask, bmp, r.roi, mirroredView)
                } catch (t: Throwable) {
                    Log.e(TAG, "segmentation failed", t)
                } finally {
                    val m = mask
                    handler.post {
                        val t1 = SystemClock.uptimeMillis()
                        if (r.gen == maskGen && m != null) {
                            uploadMask(m, r.roi)
                        }
                        // Show and record this frame with its own cut-out (and any frame just
                        // before it that could not be sent, with this very close one).
                        presentPendingUpTo(r.id)
                        bitmapPool.offer(bmp)
                        inFlight.decrementAndGet()
                        // The newest frame still waiting goes to the model straight away.
                        ring.filter { it.pending && !it.submitted }.maxByOrNull { it.id }?.let { w ->
                            if (inFlight.get() < 2) w.submitted = submitSegmentation(w)
                        }
                        noteGlBusy(SystemClock.uptimeMillis() - t1)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            bitmapPool.offer(bmp)
            inFlight.decrementAndGet()
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
        try {
            segExecutor.execute { segSamples = 0; segTimeAvg = 0f; slowReported = false }
        } catch (e: RejectedExecutionException) {
            return // the engine was released meanwhile
        }
        handler.post { glBusyMs = 0; glWindowStart = 0; glSlowWindows = 0 }
    }

    /** A square crop (in pixels) covering the whole picture, letterboxed. */
    private fun fullRoi(ww: Int, wh: Int): FloatArray {
        val side = max(ww, wh).toFloat()
        val hx = side / 2f / ww
        val hy = side / 2f / wh
        return floatArrayOf(0.5f - hx, 0.5f - hy, 0.5f + hx, 0.5f + hy)
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

    /** Shows (and records) every waiting frame up to [id], oldest first. */
    private fun presentPendingUpTo(id: Long) {
        while (true) {
            val next = ring.filter { it.pending && it.id <= id }.minByOrNull { it.id } ?: return
            present(next)
        }
    }

    // ---- speed readout (shown on screen when switched on)
    @Volatile var statsText = ""
    private var statFrames = 0
    private var statCamFrames = 0
    private var statStart = 0L

    private fun countPresented() {
        statFrames++
        val now = SystemClock.uptimeMillis()
        if (statStart == 0L) statStart = now
        val span = now - statStart
        if (span >= 1000) {
            val fps = statFrames * 1000f / span
            val cam = statCamFrames * 1000f / span
            statsText = String.format(
                java.util.Locale.US, "%.0f fps shown  ·  %.0f fps camera  ·  cut-out %.0f ms  ·  %dx%d",
                fps, cam, segTimeAvg, naturalWidth, naturalHeight,
            )
            statFrames = 0; statCamFrames = 0; statStart = now
        }
    }

    /** While recording, draw only every n-th frame on the screen (1 = all). The video gets all. */
    @Volatile var previewEveryNthWhileRecording = 1
    private var previewCounter = 0L

    private fun present(slot: Slot) {
        countPresented()
        slot.pending = false
        makeCurrentAny()
        computeAlpha(slot)
        maybeSnapshot(slot)
        val recording = encoderSurface != EGL14.EGL_NO_SURFACE
        val n = previewEveryNthWhileRecording
        if (!recording || n <= 1 || ++previewCounter % n == 0L) {
            showOnScreen(slot)
        } else if (mode == MODE_BLUR || (mode == MODE_IMAGE && !bgReady)) {
            updateBlurSource(slot) // the video's blurred background still needs this frame
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

    /** Runs the alpha pass for [slot]: its cut-out, steadied against the previous frame. */
    private fun computeAlpha(slot: Slot) {
        if (mode == MODE_NONE || !fullReady) {
            alphaReady = false
            return
        }
        val (ww, wh) = worldSize()
        if (ww == 0 || wh == 0) return
        // Enough detail for sharp edges, without costing much: 1440 px on the long side
        // (960 on weak phones).
        val cap = if (lightMode) 960f else 1440f
        val s = min(1f, cap / max(ww, wh))
        val w = (ww * s).roundToInt().coerceAtLeast(2)
        val h = (wh * s).roundToInt().coerceAtLeast(2)
        if (w != alphaW || h != alphaH) {
            if (alphaTex[0] == 0) {
                GLES30.glGenTextures(2, alphaTex, 0)
                GLES30.glGenFramebuffers(2, alphaFbo, 0)
            }
            if (alphaW != 0) {
                // Storage of a fixed size cannot be resized: make fresh textures.
                GLES30.glDeleteTextures(2, alphaTex, 0)
                GLES30.glGenTextures(2, alphaTex, 0)
            }
            for (i in 0..1) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTex[i])
                // With smaller copies (mipmaps), so the soft edge is one cheap lookup.
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 4, GLES30.GL_RGBA8, w, h)
                setLinear(GLES30.GL_TEXTURE_2D)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, alphaFbo[i])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, alphaTex[i], 0)
            }
            alphaW = w; alphaH = h
            alphaHasPrev = false
        }
        makeCurrentAny()
        val next = 1 - alphaCur
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, alphaFbo[next])
        render(alphaW, alphaH, identity(), MODE_ALPHA, slot.tex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTex[next])
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        alphaCur = next
        alphaHasPrev = true
        alphaReady = true
    }

    private fun updateBlurSource(slot: Slot) {
        makeCurrentAny()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, smallFbo)
        // Flipped so the blur texture's rows run top to bottom, like the mask.
        render(smallW, smallH, flipYC(), MODE_RAW, slot.tex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Draws [slot] on the phone screen (with the mask available now). */
    private fun showOnScreen(slot: Slot) {
        makeCurrentAny()
        if (mode == MODE_BLUR || (mode == MODE_IMAGE && !bgReady)) updateBlurSource(slot)
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            val wFromOut = mul(rotC(-deviceRotation), scaleC(cropNx, cropNy))
            focusMatrix = mul(mul(mul(stMatrix.copyOf(), fixMatrix), nFromW), wFromOut)
            EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext)
            render(previewW, previewH, wFromOut, mode, slot.tex)
            EGL14.eglSwapBuffers(display, previewSurface)
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

    private fun uploadMask(mask: PersonSegmenter.Mask, roi: FloatArray) {
        makeCurrentAny()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskFullTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mask.width, mask.height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask.data)
        )
        roi.copyInto(fullRect)
        fullReady = true
    }

    private fun invalidateMask() {
        alphaHasPrev = false
        alphaReady = false
        fullReady = false
        maskGen++
        lastFullRoi = null
        segmenter?.reset()
    }

    private fun render(w: Int, h: Int, wFromOut: FloatArray, renderMode: Int, frameTex: Int) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        // Never bind the texture we are rendering into.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (renderMode == MODE_RAW) bgTex else smallTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskFullTex)
        GLES30.glUniform1i(loc("uMaskFull"), 5)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE7)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgVideoTex)
        GLES30.glUniform1i(loc("uBgVideo"), 7)
        GLES30.glUniform1f(loc("uBgIsVideo"), if (bgIsVideo) 1f else 0f)
        GLES30.glUniformMatrix4fv(loc("uBgVideoM"), 1, false, bgVideoMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE8)
        if (renderMode == MODE_ALPHA) {
            // Read the previous result, write the other texture (never the one being read).
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTex[alphaCur])
            GLES30.glUniform1i(loc("uAlphaPrev"), 8)
            GLES30.glUniform1f(loc("uHasPrev"), if (alphaHasPrev) 1f else 0f)
            GLES30.glUniform1f(loc("uAlphaReady"), 0f)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTex[alphaCur])
            GLES30.glUniform1i(loc("uAlpha"), 8)
            GLES30.glUniform1f(loc("uAlphaReady"), if (alphaReady && alphaTex[0] != 0) 1f else 0f)
            // About 7 pixels of the alpha picture: a thin band, wider on bigger pictures.
            GLES30.glUniform2f(loc("uWrapR"), if (alphaW > 0) 7f / alphaW else 0f, if (alphaH > 0) 7f / alphaH else 0f)
        }

        GLES30.glUniform1i(loc("uFrame"), 0)
        GLES30.glUniform1i(loc("uSmall"), 2)
        GLES30.glUniform1i(loc("uBg"), 3)
        GLES30.glUniformMatrix4fv(loc("uNFromW"), 1, false, nFromW, 0)
        GLES30.glUniformMatrix4fv(loc("uWFromOut"), 1, false, wFromOut, 0)

        val effective = if (renderMode == MODE_IMAGE && !bgReady) MODE_BLUR else renderMode
        GLES30.glUniform1f(loc("uMode"), effective.toFloat())
        GLES30.glUniform1f(loc("uHasMask"), if (fullReady) 1f else 0f)
        GLES30.glUniform1f(loc("uRefine"), if (refine) 1f else 0f)
        GLES30.glUniform2f(loc("uEdge"), edgeLo, edgeHi)
        GLES30.glUniform4f(loc("uFullRect"), fullRect[0], fullRect[1], fullRect[2], fullRect[3])
        val texelRect = fullRect
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
        val t = IntArray(5)
        GLES30.glGenTextures(5, t, 0)
        oesTex = t[0]; bgTex = t[1]; smallTex = t[2]; roiTex = t[3]; maskFullTex = t[4]
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
        for (tex in intArrayOf(bgTex, smallTex, roiTex, maskFullTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            setLinear(GLES30.GL_TEXTURE_2D)
        }
        // 1x1 placeholders so every sampler is always valid
        for (tex in intArrayOf(bgTex, maskFullTex)) {
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
        private const val MAX_WAIT_MS = 1000L

        const val MODE_NONE = 0   // plain camera, no background replacement
        const val MODE_BLUR = 1   // blurred real background
        const val MODE_IMAGE = 2  // backdrop image
        private const val MODE_RAW = 3
        private const val MODE_ALPHA = 4 // steadied cut-out pass

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
uniform sampler2D uMaskFull; // person mask of the whole picture (uFullRect)
uniform vec4 uFullRect;
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
uniform sampler2D uAlpha;      // steadied cut-out of the current frame (world space)
uniform sampler2D uAlphaPrev;  // the previous one (alpha pass only): r = alpha, g = brightness
uniform float uAlphaReady;
uniform vec2 uWrapR;           // light-wrap / soft-edge radius, in alpha texture units
uniform float uHasPrev;
out vec4 outColor;

vec3 camAt(vec2 w) { return texture(uFrame, (uNFromW * vec4(w, 0.0, 1.0)).xy).rgb; }
vec2 img(vec2 w) { return vec2(w.x, 1.0 - w.y); }

// Person probability at world point w, from the whole-picture mask of this frame.
float maskRaw(vec2 w) {
    vec2 f = (w - uFullRect.xy) / (uFullRect.zw - uFullRect.xy);
    return texture(uMaskFull, vec2(f.x, 1.0 - f.y)).r;
}

// Near the edges of the camera picture the model is unsure (your body simply continues past the
// edge), so the cut-out tends to fade out there and you look like you are floating. Wherever
// you reach close to an edge, carry the cut-out straight out to that edge.
float maskAt(vec2 w) {
    float m = maskRaw(w);
    vec2 inner = clamp(w, vec2(0.06), vec2(0.94));
    if (inner != w) m = max(m, maskRaw(inner));
    return m;
}

vec3 adjust(vec3 c) {
    c *= uGain;
    c.r *= 1.0 + 0.12 * uWarm;
    c.b *= 1.0 - 0.12 * uWarm;
    return clamp(c, 0.0, 1.0);
}

float rawAlpha(vec2 p, vec3 cam) {
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
    return m;
}

void main() {
    if (uMode > 3.5) {
        // Alpha pass: the cut-out of the camera picture, steadied over time. Where the picture
        // did not change since the last frame, the edge keeps most of its previous value, so
        // model and camera noise cannot make it flicker; where you moved it follows at once.
        vec3 cam = camAt(vW);
        float a = uHasMask > 0.5 ? rawAlpha(vW, cam) : 1.0;
        vec3 soft = textureLod(uFrame, (uNFromW * vec4(vW, 0.0, 1.0)).xy, 1.0).rgb;
        float luma = dot(soft, vec3(0.299, 0.587, 0.114));
        // Colour balance too, so a hand moving over a wall of similar brightness still counts
        // as movement.
        float chroma = (soft.r - soft.b) * 0.5 + 0.5;
        if (uHasPrev > 0.5) {
            vec4 prev = textureLod(uAlphaPrev, vW, 0.0);
            float moved = max(abs(luma - prev.g), abs(chroma - prev.b));
            float k = 0.7 * (1.0 - smoothstep(0.012, 0.04, moved));
            // Only small wobbles are steadied. A big change (your hand has left this spot, or
            // just arrived) is taken at once, so no ghost of you or of the room is left behind.
            k *= 1.0 - smoothstep(0.15, 0.35, abs(a - prev.r));
            a = mix(a, prev.r, k);
        }
        outColor = vec4(a, luma, chroma, 1.0);
        return;
    }
    if (uMode > 2.5) {
        // Raw copy (crops for the model, blur source): black outside the picture.
        bool outside = vW.x < 0.0 || vW.x > 1.0 || vW.y < 0.0 || vW.y > 1.0;
        outColor = outside ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(camAt(vW), 1.0);
        return;
    }
    if (uMode < 0.5) { outColor = vec4(adjust(camAt(vW)), 1.0); return; }

    // Where this output pixel falls in the (possibly shrunk and moved) person layer.
    vec2 p = (vW - uPersonAnchor - uPersonOffset) / uPersonScale + uPersonAnchor;
    vec3 cam = camAt(p);
    float inside = smoothstep(0.0, uFeather.x, p.x) * smoothstep(0.0, uFeather.x, 1.0 - p.x)
                 * smoothstep(0.0, uFeather.x, 1.0 - p.y) * smoothstep(0.0, uFeather.y, p.y);

    // Until the cut-out is ready (the first moment after the app or the camera starts), show
    // only the backdrop or the blur: never the real room.
    float m = 0.0;
    float wrap = 0.0;
    if (uHasMask > 0.5) {
        if (uAlphaReady > 0.5) {
            // Steadied cut-out from the alpha pass.
            vec2 pc = clamp(p, 0.0, 1.0);
            float a = texture(uAlpha, pc).r;
            // How much of the area just around this point is you: one lookup in a smaller,
            // smoothed copy of the cut-out (about a 6 pixel neighbourhood).
            float ring = textureLod(uAlpha, pc, 2.3).r;
            // Softer outline: blend a little of the surrounding cut-out into the edge, so small
            // errors read as natural soft focus instead of a hard cut.
            m = mix(a, ring, 0.5);
            // Light wrap: on your side of the edge, close to the background, let some of the new
            // background's light fall onto you, as real light would.
            wrap = a * (1.0 - ring);
        } else {
            m = rawAlpha(p, cam);
        }
    }
    m = smoothstep(uEdge.x, uEdge.y, m) * inside;
    wrap = clamp(wrap * 2.0, 0.0, 1.0) * m;

    vec3 bg = vec3(0.0);
    if (m > 0.998 && wrap < 0.004) {
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
    vec3 you = adjust(cam);
    // Light wrap (screen-style, so it only ever brightens or tints a little, never darkens you).
    vec3 lit = 1.0 - (1.0 - you) * (1.0 - bg * 0.55);
    you = mix(you, lit, wrap * 0.35);
    outColor = vec4(mix(bg, you, m), 1.0);
}
"""
    }
}
