package dev.paperouette.wallpaper.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import dev.paperouette.wallpaper.PaperouetteApplication
import dev.paperouette.wallpaper.data.ActiveSelection
import dev.paperouette.wallpaper.data.PaperouetteSettings
import dev.paperouette.wallpaper.model.Remix
import dev.paperouette.wallpaper.motion.MotionController
import dev.paperouette.wallpaper.render.EglRenderHost
import dev.paperouette.wallpaper.render.FilterState
import dev.paperouette.wallpaper.render.RotationCenter
import dev.paperouette.wallpaper.render.RenderFrameState

class PaperouettePreviewView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val application = context.applicationContext as PaperouetteApplication
    private val motion = MotionController(context.applicationContext)
    private val renderHost = EglRenderHost(
        context = context,
        motionProvider = motion::snapshot,
    )
    private var windowSurface: Surface? = null
    private var currentScene: Remix =
        application.catalogue.current.value.remix(ActiveSelection.DEFAULT_REMIX)
    private var currentSettings = PaperouetteSettings()
    private var batteryPaused = false
    private var darkMode = false
    private var nextRemix: (Boolean) -> Unit = {}
    private var nextDesign: (Boolean) -> Unit = {}
    private var released = false
    private var visible = false

    init {
        surfaceTextureListener = this
        isOpaque = true
        isClickable = true
        contentDescription = "Animated wallpaper preview"
        motion.setFrameRequester(renderHost::requestFrame)
    }

    fun update(
        scene: Remix,
        settings: PaperouetteSettings,
        pauseForBattery: Boolean,
        isDarkMode: Boolean,
        onNextRemix: (Boolean) -> Unit,
        onNextDesign: (Boolean) -> Unit,
    ) {
        if (released) return
        currentScene = scene
        currentSettings = settings
        batteryPaused = pauseForBattery
        darkMode = isDarkMode
        nextRemix = onNextRemix
        nextDesign = onNextDesign
        motion.updateSettings(settings)
        updateMotionLifecycle()
        updateRenderState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released && windowVisibility == View.VISIBLE) {
            visible = true
            updateMotionLifecycle()
            renderHost.setVisible(true)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (released) return
        visible = visibility == View.VISIBLE
        updateMotionLifecycle()
        renderHost.setVisible(visible)
    }

    /**
     * Detaching only stops the rendering. The view outlives being scrolled out of the catalogue,
     * so tearing the renderer down here would rebuild it — thread, EGL context and all the
     * artwork — every time it came back. [release] is driven by whoever owns the view instead.
     */
    override fun onDetachedFromWindow() {
        if (!released) {
            visible = false
            updateMotionLifecycle()
            renderHost.setVisible(false)
        }
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (batteryPaused) return false
        val handled = motion.onTouchEvent(
            event = event,
            width = width,
            height = height,
            currentSettings = currentSettings,
            onNextRemix = { nextRemix(darkMode) },
            onNextDesign = { nextDesign(darkMode) },
        )
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return handled
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        windowSurface?.release()
        windowSurface = Surface(texture).also { surface ->
            renderHost.attachSurface(surface, width, height)
        }
        updateRenderState()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        renderHost.resize(width, height)
    }

    /**
     * Returns false to keep the `SurfaceTexture` ours to release.
     *
     * Returning true would have the framework free it the moment this returns, which is why the
     * old code waited for the GL thread here — and that wait, on the main thread, is what stalled
     * scrolling whenever the preview left the screen. Owning it lets EGL finish in its own time.
     */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val detaching = windowSurface
        windowSurface = null
        renderHost.detachSurface {
            detaching?.release()
            texture.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        if (released) return
        released = true
        motion.stop()
        renderHost.setVisible(false)
        val detaching = windowSurface
        windowSurface = null
        renderHost.release { detaching?.release() }
    }

    private fun updateRenderState() {
        renderHost.update(
            RenderFrameState(
                scene = currentScene,
                filters = FilterState(
                    dim = currentSettings.dim,
                    grayscale = currentSettings.grayscale,
                    blur = currentSettings.blur,
                ),
                rotationCenter = RotationCenter(
                    currentSettings.rotationCenterX,
                    currentSettings.rotationCenterY,
                ),
                mirrored = currentSettings.mirrored,
                rotationReversed = currentSettings.rotationReversed,
                animationPaused = batteryPaused,
                // Held still overrides the speed rather than clearing it, so turning it
                // off gives back whatever the fader was left at.
                animationSpeed = if (currentSettings.stillArtwork) {
                    0f
                } else {
                    currentSettings.animationSpeed
                },
            ),
        )
    }

    private fun updateMotionLifecycle() {
        if (visible && !batteryPaused) motion.start(currentSettings) else motion.stop()
    }
}
