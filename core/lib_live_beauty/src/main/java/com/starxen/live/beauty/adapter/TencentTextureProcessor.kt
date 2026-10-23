package com.starxen.live.beauty.adapter

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.starxen.live.media.BeautyTexture
import com.starxen.live.media.TextureBeautyProcessor
import com.starxen.live.media.TextureFrame
import com.tencent.effect.beautykit.TEBeautyKit

/** Owns one independent effect instance and at most three leased output textures.
 * Call close only after detaching the engine's frame observer. No SDK types from the live engine.
 */
class TencentTextureProcessor(
    val beautyKit: TEBeautyKit,
    private val onFailure: () -> Unit,
    private val onContextChanged: () -> Unit = onFailure
) : TextureBeautyProcessor {
    private val lock = Any()
    private var closed = false
    private var failed = false
    private var contextChanged = false
    private var processingStarted = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var sourceContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var framebuffer = 0
    private val slots = mutableListOf<Slot>()
    private var cleanupThread: HandlerThread? = null

    private class Slot(var texture: Int, var width: Int, var height: Int, var leased: Boolean = false)

    override fun process(frame: TextureFrame): BeautyTexture = synchronized(lock) {
        if (closed || failed || contextChanged) return@synchronized BeautyTexture(frame.textureId)
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (previousContext == EGL14.EGL_NO_CONTEXT) {
            Log.w("LiveBeauty", "Capture callback has no current EGL context")
            fail()
            return@synchronized BeautyTexture(frame.textureId)
        }
        try {
            GLES20.glFinish()
            if (context == EGL14.EGL_NO_CONTEXT) initialize(previousDisplay, previousContext)
            if (sourceContext != previousContext) {
                // Camera switching may replace the SDK's shared context. The owner detaches
                // this processor and rebuilds it asynchronously; old leased textures stay alive.
                contextChanged = true
                Log.i("LiveBeauty", "Capture EGL context changed; rebuilding beauty processor")
                onContextChanged()
                return@synchronized BeautyTexture(frame.textureId)
            }
            val free = slots.firstOrNull { !it.leased }
            if (free == null && slots.size >= 3) return@synchronized BeautyTexture(frame.textureId, discard = true)
            // The SDK does not expose a shared fence in the common frame protocol.
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            if (framebuffer == 0) {
                val ids = IntArray(1)
                GLES20.glGenFramebuffers(1, ids, 0)
                framebuffer = ids[0]
            }
            val output = beautyKit.process(frame.textureId, frame.width, frame.height)
            if (output <= 0) {
                Log.w("LiveBeauty", "Beauty processor returned an invalid texture")
                fail()
                return@synchronized BeautyTexture(frame.textureId)
            }
            if (!processingStarted) {
                processingStarted = true
                Log.i("LiveBeauty", "Texture processing started")
            }
            if (output == frame.textureId) return@synchronized BeautyTexture(frame.textureId)
            val slot = free ?: Slot(0, 0, 0).also { slots.add(it) }
            allocate(slot, frame.width, frame.height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, output, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture)
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, frame.width, frame.height)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            GLES20.glFinish()
            slot.leased = true
            BeautyTexture(slot.texture) {
                synchronized(lock) {
                    slot.leased = false
                    if (closed && slots.none { it.leased }) scheduleCleanup()
                }
            }
        } catch (error: RuntimeException) {
            Log.e("LiveBeauty", "Texture processing failed", error)
            fail()
            BeautyTexture(frame.textureId)
        } finally {
            if (EGL14.eglGetCurrentContext() != previousContext ||
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != previousDraw ||
                EGL14.eglGetCurrentSurface(EGL14.EGL_READ) != previousRead) {
                if (!EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)) fail()
            }
        }
    }

    private fun initialize(currentDisplay: EGLDisplay, currentContext: EGLContext) {
        display = currentDisplay
        sourceContext = currentContext
        val version = IntArray(1)
        check(EGL14.eglQueryContext(display, sourceContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, version, 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, if (version[0] >= 3) 0x40 else EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, sourceContext,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version[0], EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE)
    }

    private fun allocate(slot: Slot, width: Int, height: Int) {
        if (slot.texture != 0 && slot.width == width && slot.height == height) return
        if (slot.texture != 0) GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0)
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        slot.texture = id[0]
        slot.width = width
        slot.height = height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    }

    private fun fail() {
        if (!failed) {
            failed = true
            onFailure()
        }
    }

    override fun unavailable() = synchronized(lock) {
        if (!closed && !failed) {
            Log.w("LiveBeauty", "Capture texture format or conversion context unavailable")
            fail()
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        beautyKit.onPause()
        if (slots.none { it.leased }) scheduleCleanup()
    }

    private fun scheduleCleanup() {
        if (cleanupThread != null) return
        val thread = HandlerThread("live-beauty-release")
        cleanupThread = thread
        thread.start()
        Handler(thread.looper).post {
            synchronized(lock) {
                try {
                    if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE &&
                        EGL14.eglMakeCurrent(display, surface, surface, context)) {
                        slots.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) }
                        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                    }
                    beautyKit.onDestroy()
                } catch (error: RuntimeException) {
                    Log.e("LiveBeauty", "Failed to release beauty resources", error)
                } finally {
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                        // The display belongs to the live SDK; do not terminate it.
                        EGL14.eglReleaseThread()
                    }
                    thread.quitSafely()
                }
            }
        }
    }
}
