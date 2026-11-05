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
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
 * Every frame:
 *  1. The camera frame is drawn in W into a small offscreen texture (about 288 px on the short side).
 *  2. That exact image is read back and segmented, so the mask always matches the picture.
 *     The small texture is also the source for the "blur background" effect.
 *  3. Camera + mask + background are composited to the screen (in N) and to the encoder (in W).
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
    private var recordStartNs = 0L

    // ------------------------------------------------------------------ GL objects
    private var program = 0
    private var oesTex = 0
    private var maskTex = 0
    private var bgTex = 0
    private var smallTex = 0
    private var fbo = 0
    private var smallW = 0
    private var smallH = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private val locs = HashMap<String, Int>()

    // ------------------------------------------------------------------ camera geometry
    private var bufW = 0
    private var bufH = 0
    private var sensorRotation = 90
    private var isFront = false
    private var geometryReady = false
    private val stMatrix = FloatArray(16)
    private var fixMatrix = identity()
    private var camFromW = identity()

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

    // ------------------------------------------------------------------ segmentation
    @Volatile private var segmenter: PersonSegmenter? = null
    private val segBusy = AtomicBoolean(false)
    private var segBitmap: Bitmap? = null
    private var readBuf: ByteBuffer? = null
    private var maskReady = false
    private var maskGen = 0

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
        segExecutor.execute {
            val old = segmenter
            segmenter = newSegmenter
            old?.close()
        }
        handler.post { invalidateMask() }
    }

    fun setMode(newMode: Int) {
        handler.post { mode = newMode }
    }

    fun setBackdrop(bitmap: Bitmap) {
        handler.post {
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
                deviceRotation = degrees
                invalidateMask()
            }
        }
    }

    fun startRecording(surface: Surface, w: Int, h: Int, startNs: Long, rotation: Int) {
        handler.post {
            if (rotation != deviceRotation) {
                deviceRotation = rotation
                invalidateMask()
            }
            rotationLocked = true
            encoderSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            encoderW = w; encoderH = h
            recordStartNs = startNs
        }
    }

    /** [done] runs on the GL thread once the encoder no longer receives frames. */
    fun stopRecording(done: () -> Unit) {
        handler.post {
            if (encoderSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrentAny()
                EGL14.eglDestroySurface(display, encoderSurface)
                encoderSurface = EGL14.EGL_NO_SURFACE
            }
            rotationLocked = false
            done()
        }
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
        cameraSurface?.release()
        cameraTexture?.release()
        isFront = front
        sensorRotation = sensorDeg
        bufW = request.resolution.width
        bufH = request.resolution.height
        geometryReady = false
        invalidateMask()

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(bufW, bufH)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
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
    private fun computeGeometry() {
        val a00 = stMatrix[0]; val a10 = stMatrix[1]
        val a01 = stMatrix[4]; val a11 = stMatrix[5]
        val swapsAxes = abs(a00) < abs(a10)
        // A plain (unmirrored) SurfaceTexture transform always contains one vertical flip, so its
        // determinant is negative. Positive means the producer mirrored the image (front cameras).
        val mirrored = (a00 * a11 - a01 * a10) > 0f
        val extraRotation = if (!swapsAxes && sensorRotation % 180 != 0) sensorRotation else 0

        var fix = rotC(extraRotation)
        if (mirrored) fix = mul(fix, flipXC())
        fixMatrix = fix

        val portraitSwap = swapsAxes || extraRotation % 180 != 0
        val nw = if (portraitSwap) bufH else bufW
        val nh = if (portraitSwap) bufW else bufH
        naturalWidth = nw; naturalHeight = nh
        geometryReady = true
        Log.i(TAG, "camera ${bufW}x$bufH swaps=$swapsAxes mirrored=$mirrored extra=$extraRotation -> natural ${nw}x$nh")
        ContextCompat.getMainExecutor(appContext).execute { onGeometry?.invoke(nw, nh) }
    }

    // ================================================================== per frame

    private fun drawFrame() {
        val st = cameraTexture ?: return
        makeCurrentAny()
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        st.getTransformMatrix(stMatrix)
        if (!geometryReady) computeGeometry()

        val personMirror = if (isFront && mirrorFront) flipXC() else identity()
        camFromW = mul(mul(mul(stMatrix.copyOf(), fixMatrix), rotC(deviceRotation)), personMirror)

        ensureSmallTarget()
        renderSmallAndSegment()

        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            val wFromOut = rotC(-deviceRotation)
            focusMatrix = mul(camFromW, wFromOut)
            EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext)
            render(previewW, previewH, wFromOut, mode)
            EGL14.eglSwapBuffers(display, previewSurface)
        }
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, eglContext)
            render(encoderW, encoderH, identity(), mode)
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, System.nanoTime() - recordStartNs)
            EGL14.eglSwapBuffers(display, encoderSurface)
        }
    }

    private fun worldSize(): Pair<Int, Int> =
        if (deviceRotation % 180 == 0) naturalWidth to naturalHeight else naturalHeight to naturalWidth

    private fun ensureSmallTarget() {
        val (ww, wh) = worldSize()
        if (ww == 0 || wh == 0) return
        val scale = 288f / min(ww, wh)
        val sw = ((ww * scale).roundToInt() / 2) * 2
        val sh = ((wh * scale).roundToInt() / 2) * 2
        if (sw == smallW && sh == smallH) return
        smallW = sw; smallH = sh
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smallTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, sw, sh, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        setLinear(GLES30.GL_TEXTURE_2D)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, smallTex, 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        readBuf = ByteBuffer.allocateDirect(sw * sh * 4).order(ByteOrder.nativeOrder())
        invalidateMask()
    }

    private fun renderSmallAndSegment() {
        if (smallW == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        // Flip y so the read-back rows come out top-first (an upright Bitmap).
        render(smallW, smallH, flipYC(), MODE_RAW)

        if (mode != MODE_NONE && segmenter != null && segBusy.compareAndSet(false, true)) {
            val buf = readBuf!!
            buf.clear()
            GLES30.glReadPixels(0, 0, smallW, smallH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            var bmp = segBitmap
            if (bmp == null || bmp.width != smallW || bmp.height != smallH) {
                bmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
                segBitmap = bmp
            }
            buf.rewind()
            bmp!!.copyPixelsFromBuffer(buf)
            val gen = maskGen
            val frame = bmp
            segExecutor.execute {
                try {
                    val result = segmenter?.segment(frame)
                    if (result != null) handler.post { if (gen == maskGen) uploadMask(result) }
                } catch (t: Throwable) {
                    Log.e(TAG, "segmentation failed", t)
                } finally {
                    segBusy.set(false)
                }
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun uploadMask(mask: PersonSegmenter.Mask) {
        makeCurrentAny()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mask.width, mask.height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask.data)
        )
        maskReady = true
    }

    private fun invalidateMask() {
        maskReady = false
        maskGen++
        segmenter?.reset()
    }

    private fun render(w: Int, h: Int, wFromOut: FloatArray, renderMode: Int) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        // Never bind the texture we are rendering into.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (renderMode == MODE_RAW) bgTex else smallTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)

        GLES30.glUniform1i(loc("uCam"), 0)
        GLES30.glUniform1i(loc("uMask"), 1)
        GLES30.glUniform1i(loc("uSmall"), 2)
        GLES30.glUniform1i(loc("uBg"), 3)
        GLES30.glUniformMatrix4fv(loc("uCamFromW"), 1, false, camFromW, 0)
        GLES30.glUniformMatrix4fv(loc("uWFromOut"), 1, false, wFromOut, 0)

        val effective = if (renderMode == MODE_IMAGE && !bgReady) MODE_BLUR else renderMode
        GLES30.glUniform1f(loc("uMode"), effective.toFloat())
        GLES30.glUniform1f(loc("uHasMask"), if (maskReady) 1f else 0f)
        GLES30.glUniform1f(loc("uRefine"), if (refine) 1f else 0f)
        GLES30.glUniform2f(loc("uEdge"), edgeLo, edgeHi)
        GLES30.glUniform2f(
            loc("uTexel"),
            if (smallW > 0) 1f / smallW else 0f,
            if (smallH > 0) 1f / smallH else 0f
        )

        val (ww, wh) = worldSize()
        val worldAspect = if (wh > 0) ww.toFloat() / wh else 1f
        var sx = 1f; var sy = 1f
        if (bgAspect > worldAspect) sx = worldAspect / bgAspect else sy = bgAspect / worldAspect
        GLES30.glUniform2f(loc("uBgScale"), sx, sy)

        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loc(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

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

        program = buildProgram(VERTEX, FRAGMENT)
        val t = IntArray(4)
        GLES30.glGenTextures(4, t, 0)
        oesTex = t[0]; maskTex = t[1]; bgTex = t[2]; smallTex = t[3]
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        setLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        for (tex in intArrayOf(maskTex, bgTex, smallTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            setLinear(GLES30.GL_TEXTURE_2D)
        }
        // 1x1 placeholder so unbound samplers are always valid
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(4)
        )
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
        return p
    }

    companion object {
        private const val TAG = "CompositeEngine"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

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
uniform samplerExternalOES uCam;
uniform sampler2D uMask;
uniform sampler2D uSmall;
uniform sampler2D uBg;
uniform mat4 uCamFromW;
uniform float uMode;
uniform float uHasMask;
uniform float uRefine;
uniform vec2 uEdge;
uniform vec2 uTexel;
uniform vec2 uBgScale;
out vec4 outColor;

vec3 camAt(vec2 w) { return texture(uCam, (uCamFromW * vec4(w, 0.0, 1.0)).xy).rgb; }
vec2 img(vec2 w) { return vec2(w.x, 1.0 - w.y); }

void main() {
    vec3 cam = camAt(vW);
    if (uMode > 2.5 || uMode < 0.5) { outColor = vec4(cam, 1.0); return; }

    float m = 1.0;
    if (uHasMask > 0.5) {
        if (uRefine > 0.5) {
            // Joint bilateral up-sampling: the low-res mask follows the full-res colour edges.
            float s = 0.0;
            float ws = 0.0;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    vec2 w = vW + vec2(float(i), float(j)) * uTexel * 1.5;
                    vec3 d = camAt(w) - cam;
                    float wt = exp(-dot(d, d) * 30.0);
                    s += texture(uMask, img(w)).r * wt;
                    ws += wt;
                }
            }
            m = s / ws;
        } else {
            m = texture(uMask, img(vW)).r;
        }
    }
    m = smoothstep(uEdge.x, uEdge.y, m);

    vec3 bg;
    if (uMode < 1.5) {
        vec2 t = img(vW);
        vec3 acc = vec3(0.0);
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                acc += texture(uSmall, t + vec2(float(i), float(j)) * uTexel * 2.5).rgb;
            }
        }
        bg = acc / 25.0;
    } else {
        bg = texture(uBg, (img(vW) - 0.5) * uBgScale + 0.5).rgb;
    }
    outColor = vec4(mix(bg, cam, m), 1.0);
}
"""
    }
}
