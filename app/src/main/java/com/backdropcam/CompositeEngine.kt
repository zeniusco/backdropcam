package com.backdropcam

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
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/**
 * OpenGL ES 3.0 pipeline that runs on its own thread:
 *
 *   camera (OES texture, full resolution)  ┐
 *   person mask (R8 texture, 256x256)      ├─> shader ─> preview surface
 *   backdrop (RGBA texture)                ┘          └> encoder surface (while recording)
 *
 * Everything is rendered in "upright" orientation, so the output video is already rotated.
 */
class CompositeEngine {

    private val thread = HandlerThread("composite-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    // EGL
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0
    private var encoderW = 0
    private var encoderH = 0

    // GL objects
    private var program = 0
    private var oesTex = 0
    private var maskTex = 0
    private var bgTex = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val camMatrix = FloatArray(16)
    private val tmpMatrix = FloatArray(16)
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v (u,v are "upright" coordinates, v up)
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    // State (touched only on the GL thread)
    private var cameraRotation = 0
    private var cameraBufferW = 0
    private var cameraBufferH = 0
    private var mirror = false
    private var maskReady = false
    private var bgReady = false
    private var bgAspect = 1f
    private var recordStartNs = 0L

    /** Called on the main thread once the camera has delivered its buffer size and rotation. */
    var onOutputSize: ((width: Int, height: Int) -> Unit)? = null

    /** Upright output size in pixels (width < height in portrait). 0 until the camera is bound. */
    @Volatile var outputWidth = 0; private set
    @Volatile var outputHeight = 0; private set

    init {
        handler.post { initGl() }
    }

    // ---------------------------------------------------------------- public API

    fun setPreviewSurface(surface: Surface, w: Int, h: Int) = handler.post {
        destroyPreview()
        previewSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        previewW = w; previewH = h
    }

    fun clearPreviewSurface() = handler.post { destroyPreview() }

    /** SurfaceProvider for CameraX Preview. */
    fun surfaceProvider(isFront: Boolean) = androidx.camera.core.Preview.SurfaceProvider { request ->
        handler.post { attachCamera(request, isFront) }
    }

    fun setBackdrop(bitmap: Bitmap?) = handler.post {
        if (bitmap == null) {
            bgReady = false
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            setLinear(GLES30.GL_TEXTURE_2D)
            bgAspect = bitmap.width.toFloat() / bitmap.height
            bgReady = true
        }
    }

    fun updateMask(data: ByteArray, w: Int, h: Int) {
        handler.post {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(data)
            )
            maskReady = true
        }
    }

    fun startRecording(surface: Surface, w: Int, h: Int, startNs: Long) = handler.post {
        encoderSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        encoderW = w; encoderH = h
        recordStartNs = startNs
    }

    /** [done] runs on the GL thread after the encoder surface was detached. */
    fun stopRecording(done: () -> Unit) = handler.post {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
        done()
    }

    fun release() {
        handler.post {
            destroyPreview()
            cameraSurface?.release(); cameraTexture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- camera input

    private fun attachCamera(request: SurfaceRequest, isFront: Boolean) {
        cameraSurface?.release()
        cameraTexture?.release()
        mirror = isFront
        val res = request.resolution
        cameraBufferW = res.width
        cameraBufferH = res.height
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(res.width, res.height)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        val surface = Surface(st)
        cameraTexture = st
        cameraSurface = surface
        maskReady = false

        request.setTransformationInfoListener(glExecutor) { info ->
            cameraRotation = info.rotationDegrees
            val swap = cameraRotation % 180 != 0
            val w = if (swap) cameraBufferH else cameraBufferW
            val h = if (swap) cameraBufferW else cameraBufferH
            outputWidth = w; outputHeight = h
            ContextCompat.getMainExecutor(appContext()).execute { onOutputSize?.invoke(w, h) }
        }
        request.provideSurface(surface, glExecutor) {
            // CameraX is done with this surface
            if (cameraSurface === surface) {
                surface.release(); st.release()
                cameraSurface = null; cameraTexture = null
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun drawFrame() {
        val st = cameraTexture ?: return
        if (previewSurface == EGL14.EGL_NO_SURFACE && encoderSurface == EGL14.EGL_NO_SURFACE) {
            // Keep draining frames so the camera does not stall.
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            st.updateTexImage()
            return
        }
        val target = if (previewSurface != EGL14.EGL_NO_SURFACE) previewSurface else encoderSurface
        EGL14.eglMakeCurrent(display, target, target, context)
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        // upright uv -> rotate about centre -> SurfaceTexture transform -> sampler uv
        Matrix.setIdentityM(tmpMatrix, 0)
        Matrix.translateM(tmpMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(tmpMatrix, 0, cameraRotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(tmpMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(camMatrix, 0, stMatrix, 0, tmpMatrix, 0)

        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, previewSurface, previewSurface, context)
            render(previewW, previewH)
            EGL14.eglSwapBuffers(display, previewSurface)
        }
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context)
            render(encoderW, encoderH)
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, System.nanoTime() - recordStartNs)
            EGL14.eglSwapBuffers(display, encoderSurface)
        }
    }

    private fun render(w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)

        GLES30.glUniform1i(loc("uCam"), 0)
        GLES30.glUniform1i(loc("uMask"), 1)
        GLES30.glUniform1i(loc("uBg"), 2)
        GLES30.glUniformMatrix4fv(loc("uCamMat"), 1, false, camMatrix, 0)
        GLES30.glUniform1f(loc("uMirror"), if (mirror) 1f else 0f)
        GLES30.glUniform1f(loc("uHasBg"), if (bgReady) 1f else 0f)
        GLES30.glUniform1f(loc("uHasMask"), if (maskReady) 1f else 0f)
        GLES30.glUniform2f(loc("uEdge"), 0.35f, 0.70f)

        // "cover" fit of the backdrop into the output aspect ratio
        val outAspect = w.toFloat() / h
        var sx = 1f; var sy = 1f
        if (bgAspect > outAspect) sx = outAspect / bgAspect else sy = bgAspect / outAspect
        GLES30.glUniform2f(loc("uBgScale"), sx, sy)

        quad.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private val locs = HashMap<String, Int>()
    private fun loc(name: String) = locs.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    // ---------------------------------------------------------------- setup

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram(VERTEX, FRAGMENT)
        val t = IntArray(3)
        GLES30.glGenTextures(3, t, 0)
        oesTex = t[0]; maskTex = t[1]; bgTex = t[2]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        setLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        setLinear(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
        setLinear(GLES30.GL_TEXTURE_2D)
    }

    private fun setLinear(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun destroyPreview() {
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
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
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aUv")
        GLES30.glLinkProgram(p)
        return p
    }

    private fun appContext() = BackdropApp.instance

    companion object {
        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uCamMat;
uniform float uMirror;
uniform vec2 uBgScale;
out vec2 vCam;
out vec2 vMask;
out vec2 vBg;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vec2 uv = vec2(mix(aUv.x, 1.0 - aUv.x, uMirror), aUv.y);
    vCam = (uCamMat * vec4(uv, 0.0, 1.0)).xy;
    vMask = vec2(uv.x, 1.0 - uv.y);
    vBg = (vec2(aUv.x, 1.0 - aUv.y) - 0.5) * uBgScale + 0.5;
}
"""
        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vCam;
in vec2 vMask;
in vec2 vBg;
uniform samplerExternalOES uCam;
uniform sampler2D uMask;
uniform sampler2D uBg;
uniform float uHasBg;
uniform float uHasMask;
uniform vec2 uEdge;
out vec4 outColor;
void main() {
    vec3 cam = texture(uCam, vCam).rgb;
    float m = texture(uMask, vMask).r;
    m = mix(1.0, smoothstep(uEdge.x, uEdge.y, m), uHasMask);
    vec3 bg = mix(vec3(0.05, 0.75, 0.25), texture(uBg, vBg).rgb, uHasBg);
    outColor = vec4(mix(bg, cam, m), 1.0);
}
"""
    }
}
