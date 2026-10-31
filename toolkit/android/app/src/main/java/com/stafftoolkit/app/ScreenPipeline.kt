package com.stafftoolkit.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Takes the screen image (from screen capture) and shares it out: to the video encoder,
 * and as still pictures for screenshots and chat reading. Android only allows one screen
 * capture at a time, so everything uses this one.
 */
class ScreenPipeline(val width: Int, val height: Int) {
    private val thread = HandlerThread("screen-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderFps = 60
    private var lastEncoded = 0L
    private var program = 0
    private var texId = 0
    private var hasFrame = false
    private val texMatrix = FloatArray(16)
    private lateinit var texture: SurfaceTexture
    lateinit var surface: Surface
        private set

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        val ready = CountDownLatch(1)
        handler.post { setup(); ready.countDown() }
        ready.await()
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, idle, idle, context)

        program = buildProgram()
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        texture = SurfaceTexture(texId)
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener({ onFrame() }, handler)
        surface = Surface(texture)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTexM; varying vec2 vTex;
            void main() { gl_Position = vec4(aPos.xy, 0.0, 1.0); vTex = (uTexM * aTex).xy; }
        """.trimIndent())
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent())
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun draw(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexM"), 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun onFrame() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, idle, idle, context)
        try { texture.updateTexImage() } catch (_: Exception) { return }
        texture.getTransformMatrix(texMatrix)
        hasFrame = true
        val ts = texture.timestamp
        if (encoderSurface != EGL14.EGL_NO_SURFACE && ts - lastEncoded >= 1_000_000_000L / encoderFps * 9 / 10) {
            lastEncoded = ts
            EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context)
            draw(width, height)
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, ts)
            EGL14.eglSwapBuffers(display, encoderSurface)
            EGL14.eglMakeCurrent(display, idle, idle, context)
        }
    }

    /** Starts sending the screen to a video encoder's input. */
    fun attachEncoder(input: Surface, fps: Int) {
        val done = CountDownLatch(1)
        handler.post {
            if (encoderSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderSurface)
            encoderSurface = EGL14.eglCreateWindowSurface(display, config, input, intArrayOf(EGL14.EGL_NONE), 0)
            encoderFps = fps.coerceIn(10, 120)
            lastEncoded = 0
            done.countDown()
        }
        done.await()
    }

    fun detachEncoder() {
        val done = CountDownLatch(1)
        handler.post {
            if (encoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, idle, idle, context)
                EGL14.eglDestroySurface(display, encoderSurface)
                encoderSurface = EGL14.EGL_NO_SURFACE
            }
            done.countDown()
        }
        done.await()
    }

    /** A still picture of the screen, [scale] = size (1 = full). Null if nothing has been shown yet. */
    fun grab(scale: Float = 1f): Bitmap? {
        var out: Bitmap? = null
        val done = CountDownLatch(1)
        handler.post {
            try {
                if (hasFrame) {
                    val w = (width * scale).toInt().coerceAtLeast(16)
                    val h = (height * scale).toInt().coerceAtLeast(16)
                    val pb = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
                    EGL14.eglMakeCurrent(display, pb, pb, context)
                    draw(w, h)
                    val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                    GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    EGL14.eglMakeCurrent(display, idle, idle, context)
                    EGL14.eglDestroySurface(display, pb)
                    buf.rewind()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buf)
                    // The picture comes out upside down from the graphics chip
                    out = Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
                    bmp.recycle()
                }
            } catch (_: Throwable) {
                out = null
            }
            done.countDown()
        }
        done.await()
        return out
    }

    fun release() {
        handler.post {
            try {
                surface.release()
                texture.release()
                if (encoderSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderSurface)
                EGL14.eglDestroySurface(display, idle)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Throwable) {}
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
