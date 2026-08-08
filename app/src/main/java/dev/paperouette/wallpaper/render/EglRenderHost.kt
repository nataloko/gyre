package dev.paperouette.wallpaper.render

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlin.math.max

/** Owns an EGL window surface and renders only while its host is visible. */
internal class EglRenderHost(
    context: Context,
    private val motionProvider: (Long) -> MotionSample,
) {
    private class EglFailure(val errorCode: Int, operation: String) :
        IllegalStateException("$operation failed: 0x${errorCode.toString(16)}")

    private val applicationContext = context.applicationContext
    private val thread = HandlerThread("PaperouetteGL").apply { start() }
    private val handler = Handler(thread.looper)
    private var renderer = SceneRenderer(applicationContext)
    private val animationClock = AnimationClock()
    private val framePacer = FramePacer()

    @Volatile
    private var frameState: RenderFrameState? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var window: Surface? = null
    private var viewport: Viewport? = null
    private var visible = false
    private var initialized = false
    private var frameScheduled = false
    private var released = false
    private var blocked = false
    private var blockedState: RenderFrameState? = null
    private var consecutiveEglFailures = 0
    private var requestedFrameRate = 0f

    private val trimResources = Runnable {
        if (released || visible) return@Runnable
        if (surface != EGL14.EGL_NO_SURFACE) {
            if (!initialized) return@Runnable
            runCatching {
                makeCurrent()
                renderer.trimMemory()
            }.onFailure { Log.w(TAG, "Memory trim failed", it) }
        } else if (display != EGL14.EGL_NO_DISPLAY) {
            // Detached rather than merely hidden, and it has stayed away. There is no surface to
            // make current for a trim, so drop the context instead — its textures die with it, and
            // reattaching builds a fresh one.
            runCatching { destroyEgl(releaseRenderer = true) }
                .onFailure { Log.w(TAG, "Detached teardown failed", it) }
        }
    }

    private val renderFrame = object : Runnable {
        override fun run() {
            frameScheduled = false
            if (!canRender()) return
            val state = frameState ?: return
            val currentViewport = viewport ?: return
            try {
                makeCurrent()
                val now = System.nanoTime()
                val motion = motionProvider(now)
                val animationNanos = animationClock.sample(
                    monotonicNanos = now,
                    running = visible && !state.animationPaused,
                    speed = state.animationSpeed,
                )
                renderer.render(
                    scene = state.scene,
                    viewport = currentViewport,
                    monotonicNanos = animationNanos,
                    motion = motion.state,
                    filters = state.filters,
                    rotationCenter = state.rotationCenter,
                    mirrored = state.mirrored,
                    rotationReversed = state.rotationReversed,
                )
                if (!EGL14.eglSwapBuffers(display, surface)) {
                    throw EglFailure(EGL14.eglGetError(), "eglSwapBuffers")
                }
                consecutiveEglFailures = 0
                blockedState = null
                // A scene held still has nothing to draw until something moves, so it stops asking
                // for frames entirely rather than redrawing an identical one thirty times a second.
                // Touch and the sensors request their own frames, so it comes straight back.
                val animating = state.animationSpeed > 0f || motion.highMotion
                if (visible && !state.animationPaused && animating) {
                    requestSurfaceFrameRate(motion.highMotion)
                    scheduleNextFrame(now, motion.highMotion)
                }
            } catch (error: Throwable) {
                handleRenderFailure(error, state)
            }
        }
    }

    fun attachSurface(window: Surface, width: Int, height: Int) {
        handler.post {
            this.window = window
            if (width > 0 && height > 0) {
                viewport = Viewport(width, height)
                attachWindowSurface(window)
            } else {
                // Keep the window so resize() can attach once real dimensions arrive.
                viewport = null
                destroyWindowSurface(releaseRenderer = true)
            }
        }
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        handler.post {
            viewport = Viewport(width, height)
            val pendingWindow = window
            if (surface == EGL14.EGL_NO_SURFACE && pendingWindow != null) {
                attachWindowSurface(pendingWindow)
            } else {
                requestFrameNow()
            }
        }
    }

    private fun attachWindowSurface(window: Surface) {
        blocked = false
        consecutiveEglFailures = 0
        runCatching {
            destroyWindowSurface(releaseRenderer = true)
            ensureContext()
            createWindowSurface(window)
            makeCurrent()
            requestFrameNow()
        }.onFailure { handleRenderFailure(it, frameState) }
    }

    /**
     * Lets go of the window surface, without waiting for the GL thread to get to it.
     *
     * Waiting here is what stalled scrolling: the caller is the main thread, and the GL thread can
     * be a full second into decoding a scene's 2600×2600 layers. [onDetached] runs on the GL thread
     * once EGL has finished with the window, which is the moment it becomes safe to release the
     * `SurfaceTexture` behind it.
     */
    fun detachSurface(onDetached: (() -> Unit)? = null) {
        handler.post {
            handler.removeCallbacks(trimResources)
            // The renderer's textures belong to the EGL context, which outlives the window surface.
            // Keeping them means scrolling the preview away and back does not decode the artwork
            // again; the delayed trim reclaims them if it stays away.
            destroyWindowSurface(releaseRenderer = false)
            window = null
            viewport = null
            handler.postDelayed(trimResources, HIDDEN_TRIM_DELAY_MS)
            onDetached?.invoke()
        }
    }

    fun setVisible(isVisible: Boolean) {
        handler.post {
            visible = isVisible
            handler.removeCallbacks(trimResources)
            animationClock.resetBaseline()
            framePacer.reset()
            if (isVisible) {
                blocked = false
                consecutiveEglFailures = 0
                requestFrameNow()
            } else {
                handler.removeCallbacks(renderFrame)
                frameScheduled = false
                handler.postDelayed(trimResources, HIDDEN_TRIM_DELAY_MS)
            }
        }
    }

    fun update(state: RenderFrameState) {
        val previous = frameState
        frameState = state
        handler.post {
            if (blockedState != null && state != blockedState) {
                blocked = false
                blockedState = null
            }
            // Also on a speed change, and not only because the cadence moved: a scene held still
            // stops drawing, so the last tick can be minutes old, and resuming without rebasing
            // would hand all of that to the timeline at once and jump the artwork.
            val rebase = previous == null ||
                previous.animationPaused != state.animationPaused ||
                previous.animationSpeed != state.animationSpeed
            if (rebase) {
                animationClock.resetBaseline()
                framePacer.reset()
            }
            requestFrameNow()
        }
    }

    fun requestFrame() {
        handler.post { requestFrameNow() }
    }

    fun trimMemory() {
        handler.post {
            handler.removeCallbacks(trimResources)
            if (initialized && surface != EGL14.EGL_NO_SURFACE) {
                runCatching {
                    makeCurrent()
                    renderer.trimMemory()
                }.onFailure { Log.w(TAG, "Immediate memory trim failed", it) }
            }
        }
    }

    /** Tears everything down on the GL thread and stops it. Nothing waits for this to finish. */
    fun release(onReleased: (() -> Unit)? = null) {
        if (released) return
        released = true
        handler.post {
            handler.removeCallbacksAndMessages(null)
            destroyEgl(releaseRenderer = true)
            window = null
            viewport = null
            onReleased?.invoke()
            thread.quitSafely()
        }
    }

    private fun canRender(): Boolean =
        !released && !blocked && visible && surface != EGL14.EGL_NO_SURFACE && frameState != null

    private fun ensureContext() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw EglFailure(EGL14.eglGetError(), "eglInitialize")
        }
        if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
            throw EglFailure(EGL14.eglGetError(), "eglBindAPI")
        }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0)) {
            throw EglFailure(EGL14.eglGetError(), "eglChooseConfig")
        }
        check(count[0] > 0) { "No GLES 3 EGL config" }
        config = requireNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw EglFailure(EGL14.eglGetError(), "eglCreateContext")
        }
    }

    private fun createWindowSurface(window: Surface) {
        surface = EGL14.eglCreateWindowSurface(
            display,
            config,
            window,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) {
            throw EglFailure(EGL14.eglGetError(), "eglCreateWindowSurface")
        }
    }

    private fun makeCurrent() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
            throw EglFailure(EGL14.eglGetError(), "eglMakeCurrent")
        }
        if (!initialized) {
            renderer.initialize()
            EGL14.eglSwapInterval(display, 1)
            initialized = true
        }
    }

    private fun destroyWindowSurface(releaseRenderer: Boolean) {
        handler.removeCallbacks(renderFrame)
        frameScheduled = false
        animationClock.resetBaseline()
        framePacer.reset()
        requestedFrameRate = 0f
        if (releaseRenderer && initialized && surface != EGL14.EGL_NO_SURFACE) {
            runCatching {
                if (EGL14.eglMakeCurrent(display, surface, surface, eglContext)) renderer.release()
            }
            initialized = false
            renderer = SceneRenderer(applicationContext)
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        }
        if (surface != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(display, surface)
        }
        surface = EGL14.EGL_NO_SURFACE
    }

    private fun destroyEgl(releaseRenderer: Boolean) {
        destroyWindowSurface(releaseRenderer)
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, eglContext)
            }
            EGL14.eglTerminate(display)
        }
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
        initialized = false
        renderer = SceneRenderer(applicationContext)
    }

    private fun handleRenderFailure(error: Throwable, state: RenderFrameState?) {
        Log.e(TAG, "Render failed", error)
        if (error is EglFailure && error.errorCode in RECOVERABLE_EGL_ERRORS) {
            consecutiveEglFailures += 1
            if (consecutiveEglFailures == 1 && recoverEgl()) return
        }
        blocked = true
        blockedState = state
    }

    private fun recoverEgl(): Boolean = runCatching {
        val currentWindow = requireNotNull(window) { "No window available for EGL recovery" }
        destroyEgl(releaseRenderer = false)
        ensureContext()
        createWindowSurface(currentWindow)
        makeCurrent()
        requestFrameNow()
        true
    }.getOrElse { recoveryError ->
        Log.e(TAG, "EGL recovery failed", recoveryError)
        false
    }

    private fun requestFrameNow() {
        if (!canRender()) return
        handler.removeCallbacks(renderFrame)
        frameScheduled = true
        handler.post(renderFrame)
    }

    private fun scheduleNextFrame(renderNanos: Long, highMotion: Boolean) {
        if (!canRender() || frameScheduled) return
        val deadline = framePacer.nextDeadline(renderNanos, highMotion)
        val delayMillis = max(0L, (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND)
        frameScheduled = true
        handler.postDelayed(renderFrame, delayMillis)
    }

    private fun requestSurfaceFrameRate(highMotion: Boolean) {
        val requested = if (highMotion) FramePacer.INTERACTIVE_FPS else FramePacer.IDLE_FPS
        if (requested == requestedFrameRate) return
        runCatching {
            window?.setFrameRate(
                requested,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
            requestedFrameRate = requested
        }.onFailure { Log.w(TAG, "Surface frame-rate request failed", it) }
    }

    private companion object {
        const val TAG = "PaperouetteGL"
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        const val HIDDEN_TRIM_DELAY_MS = 5_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val RECOVERABLE_EGL_ERRORS = setOf(EGL14.EGL_CONTEXT_LOST, EGL14.EGL_BAD_SURFACE)
    }
}
