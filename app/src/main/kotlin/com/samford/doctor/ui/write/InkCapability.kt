package com.samford.doctor.ui.write

import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.Log

enum class WetInkMode { AUTO, LOW_LATENCY, STANDARD }

/**
 * Chooses the wet-ink path. The low-latency path (Jetpack Ink, front-buffered) needs hardware
 * front-buffer support and a real GPU: under a software GL renderer (the SwiftShader used by
 * emulators) front-buffered rendering crashed the platform renderer (libhwui DrawFrameTask), so
 * such devices get the standard canvas path. Every recorded stroke and every page is identical on
 * both paths; only how fast the in-flight stroke appears differs.
 */
object InkCapability {
    data class Report(val frontBufferSupported: Boolean, val glRenderer: String?) {
        val softwareRenderer: Boolean get() = isSoftwareRenderer(glRenderer)
        val lowLatency: Boolean get() = frontBufferSupported && glRenderer != null && !softwareRenderer
    }

    /** Test hook (instrumentation argument samfordWetInk); always AUTO in the app. */
    @Volatile
    var mode: WetInkMode = WetInkMode.AUTO

    @Volatile
    private var cached: Report? = null

    fun useLowLatency(): Boolean = when (mode) {
        WetInkMode.LOW_LATENCY -> true
        WetInkMode.STANDARD -> false
        WetInkMode.AUTO -> report().lowLatency
    }

    @Synchronized
    fun report(): Report = cached ?: probe().also {
        cached = it
        Log.i("InkCapability", "front buffer=${it.frontBufferSupported} renderer=${it.glRenderer} lowLatency=${it.lowLatency}")
    }

    private val softwareMarkers = listOf("swiftshader", "llvmpipe", "softpipe", "lavapipe", "software")

    fun isSoftwareRenderer(renderer: String?): Boolean {
        val r = renderer?.lowercase() ?: return false
        return softwareMarkers.any { it in r }
    }

    private fun probe(): Report {
        if (Build.FINGERPRINT == "robolectric") return Report(frontBufferSupported = false, glRenderer = null)
        val frontBuffer = runCatching {
            val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_COMPOSER_OVERLAY or HardwareBuffer.USAGE_FRONT_BUFFER
            HardwareBuffer.isSupported(1, 1, HardwareBuffer.RGBA_8888, 1, usage)
        }.getOrDefault(false)
        return Report(frontBuffer, runCatching { glRenderer() }.getOrNull())
    }

    /** GL_RENDERER from a throwaway 1×1 pbuffer context, released afterwards on this thread. */
    private fun glRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        // The default display is shared with the platform renderer in this process: never terminate it.
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        return try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) null else GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
