package dev.gyre.wallpaper.wallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import dev.gyre.wallpaper.GyreApplication
import dev.gyre.wallpaper.data.ActiveSelection
import dev.gyre.wallpaper.data.GyreSettings
import dev.gyre.wallpaper.motion.MotionController
import dev.gyre.wallpaper.render.EglRenderHost
import dev.gyre.wallpaper.render.FilterState
import dev.gyre.wallpaper.render.RenderFrameState
import dev.gyre.wallpaper.render.RotationCenter
import dev.gyre.wallpaper.render.SceneTone
import dev.gyre.wallpaper.render.WallpaperPaletteTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GyreWallpaperService : WallpaperService() {
    private val engines = mutableSetOf<GyreEngine>()

    /** Shared across engines: the measurement is the same for every one of them. */
    private val tone by lazy { SceneTone(this) }

    override fun onCreateEngine(): Engine = GyreEngine().also(engines::add)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engines.toList().forEach(GyreEngine::onSystemAppearanceChanged)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engines.toList().forEach(GyreEngine::trimMemory)
    }

    private inner class GyreEngine : Engine() {
        private val application = this@GyreWallpaperService.application as GyreApplication
        private val catalogue = application.catalogue
        private val repository = application.settings
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val powerManager = getSystemService(PowerManager::class.java)
        private val motion = MotionController(this@GyreWallpaperService)
        private val renderHost = EglRenderHost(
            context = this@GyreWallpaperService,
            motionProvider = motion::snapshot,
        )
        private var currentSettings = GyreSettings()
        private var currentSelection = ActiveSelection()
        private var currentFilters = FilterState()
        private var visible = false
        private var changeTicker: Job? = null

        /** The interval [changeTicker] is running for; 0 whenever there is no ticker. */
        private var tickingFor = 0

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateMotionLifecycle()
                updateRenderState()
            }
        }

        init {
            setTouchEventsEnabled(true)
            registerReceiver(
                powerReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
            motion.setFrameRequester(renderHost::requestFrame)
            scope.launch {
                repository.settings
                    .map { settings ->
                        settings.copy(dim = 0f, grayscale = 0f, blur = 0f, favorites = emptySet())
                    }
                    .distinctUntilChanged()
                    .collect { settings ->
                        currentSettings = settings
                        motion.updateSettings(settings)
                        updateMotionLifecycle()
                        updateChangeTicker()
                        updateRenderState()
                    }
            }
            scope.launch {
                repository.settings
                    .map { FilterState(dim = it.dim, grayscale = it.grayscale, blur = it.blur) }
                    .distinctUntilChanged()
                    .collect { filters ->
                        currentFilters = filters
                        updateRenderState()
                        notifyColorsChanged()
                    }
            }
            scope.launch {
                repository.selection.collect { selection ->
                    currentSelection = selection
                    updateRenderState()
                    notifyColorsChanged()
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val frame = holder.surfaceFrame
            renderHost.attachSurface(holder.surface, frame.width(), frame.height())
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            renderHost.resize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderHost.detachSurface()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                // Catches up on a theme change that arrived while the engine was down. It costs a
                // read and nothing else when the theme has not moved, which is the usual case —
                // resolving here unconditionally is what used to undo the user's own pick every
                // time the home screen came forward.
                //
                // The timed change follows it in the same coroutine rather than beside it, so the
                // order is settled: a theme change moves the artwork and so starts the interval
                // again, instead of the two racing to decide what the wallpaper became.
                scope.launch {
                    repository.selectForDarkMode(isDarkMode())
                    if (!isPreview) repository.shuffleIfDue(isDarkMode())
                }
            }
            updateChangeTicker()
            updateMotionLifecycle()
            renderHost.setVisible(isVisible)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            motion.setLauncherOffsets(xOffset, yOffset)
        }

        /**
         * The launcher zooming away to its app drawer or its recents view.
         *
         * The app has no equivalent — there is no launcher behind its own stage — so this is the one
         * thing the wallpaper does that the preview cannot show.
         */
        override fun onZoomChanged(zoom: Float) {
            motion.setLauncherZoom(zoom)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (isBatteryPaused()) {
                super.onTouchEvent(event)
                return
            }
            motion.onTouchEvent(
                event = event,
                width = surfaceHolder.surfaceFrame.width(),
                height = surfaceHolder.surfaceFrame.height(),
                currentSettings = currentSettings,
                onNextRemix = { scope.launch { repository.nextRemix(isDarkMode()) } },
                onNextDesign = { scope.launch { repository.nextDesign(isDarkMode()) } },
            )
            super.onTouchEvent(event)
        }

        override fun onComputeColors(): WallpaperColors {
            val remix = activeRemix()
            val palette = WallpaperPaletteTransform.apply(
                remix.colors,
                currentFilters,
                tone.dominantColor(remix),
            )
            // The hints are stated rather than left to be inferred from the colours. The system
            // draws the clock and the status icons from them, and inferring "light wallpaper" from
            // a bright accent is how a black wallpaper ended up with black icons on it.
            return WallpaperColors(
                Color.valueOf(palette.primary),
                palette.secondary?.let(Color::valueOf),
                palette.tertiary?.let(Color::valueOf),
                if (palette.suitsDarkText) {
                    WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                } else {
                    WallpaperColors.HINT_SUPPORTS_DARK_THEME
                },
            )
        }

        override fun onDestroy() {
            runCatching { unregisterReceiver(powerReceiver) }
            motion.stop()
            renderHost.release()
            scope.cancel()
            engines.remove(this)
            super.onDestroy()
        }

        fun onSystemAppearanceChanged() {
            if (visible) scope.launch { repository.selectForDarkMode(isDarkMode()) }
            updateRenderState()
        }

        fun trimMemory() = renderHost.trimMemory()

        /**
         * The selected artwork, or the default when it has gone.
         *
         * The settings repository resolves the selection against the catalogue, so this is only
         * ever reached in the instant between an import being removed and that resolution
         * arriving — but the engine draws on every frame and must not throw in it.
         */
        private fun activeRemix() = catalogue.current.value.let { snapshot ->
            snapshot.remixOrNull(currentSelection.remixId)
                ?: snapshot.remixOrNull(ActiveSelection.DEFAULT_REMIX)
                ?: snapshot.remixes.first()
        }

        private fun updateRenderState() {
            renderHost.update(
                RenderFrameState(
                    scene = activeRemix(),
                    filters = currentFilters,
                    rotationCenter = RotationCenter(
                        currentSettings.rotationCenterX,
                        currentSettings.rotationCenterY,
                    ),
                    mirrored = currentSettings.mirrored,
                    rotationReversed = currentSettings.rotationReversed,
                    animationPaused = isBatteryPaused(),
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
            if (visible && !isBatteryPaused()) {
                motion.start(currentSettings)
            } else {
                motion.stop()
            }
        }

        /**
         * Re-checks the timed change while the wallpaper is actually being looked at.
         *
         * [onVisibilityChanged] covers the ordinary case: the interval runs out with the phone in a
         * pocket, and the new artwork is there on the next look. This covers the other one, a phone
         * left on the home screen past the interval, which would otherwise wait for the screen to
         * go off and come back. It runs only while visible and only while the setting is on, and a
         * minute's tick costs nothing beside the frame loop it sits next to.
         *
         * The engine's scope owns it, so `onDestroy`'s cancel already takes it with the rest.
         *
         * Never in a preview engine, and neither is the check in [onVisibilityChanged]. The system
         * wallpaper picker runs one of these to show what is about to be set, and swapping the
         * artwork out from under that — spending the interval on it too — is the one place a
         * wallpaper changing itself is plainly wrong.
         */
        private fun updateChangeTicker() {
            // Restarted only when the thing it depends on moves, not on every settings emission:
            // this is called from the same collector a fader writes to, and a ticker rebuilt on
            // each write would have its minute reset for as long as anyone kept dragging.
            val wanted = if (visible && !isPreview) currentSettings.randomChangeHours else 0
            if (wanted == tickingFor) return
            tickingFor = wanted
            changeTicker?.cancel()
            changeTicker = if (wanted > 0) {
                scope.launch {
                    while (isActive) {
                        delay(CHANGE_CHECK_MILLIS)
                        repository.shuffleIfDue(isDarkMode())
                    }
                }
            } else {
                null
            }
        }

        /**
         * Deliberately independent of [visible].
         *
         * It used to require it, which made the state pushed to the renderer depend on when it was
         * computed: [onVisibilityChanged] does not recompute it, so a wallpaper that became visible
         * with the saver already on carried a stale `animationPaused = false` and kept turning at
         * 30 fps while its sensors were stopped — half paused, and only corrected by the next
         * settings emission or a power broadcast that had already fired.
         *
         * Visibility still gates the rendering, one layer down: `EglRenderHost` only advances the
         * animation clock while `visible && !animationPaused`.
         */
        private fun isBatteryPaused(): Boolean =
            currentSettings.pauseOnBatterySaver && powerManager.isPowerSaveMode

        private fun isDarkMode(): Boolean =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        /** How often a visible wallpaper asks whether its interval is out. */
        const val CHANGE_CHECK_MILLIS = 60_000L
    }
}
