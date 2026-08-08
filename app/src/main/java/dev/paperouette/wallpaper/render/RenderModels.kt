package dev.paperouette.wallpaper.render

import dev.paperouette.wallpaper.model.Layer
import dev.paperouette.wallpaper.model.PaletteColors
import dev.paperouette.wallpaper.model.RampStop
import dev.paperouette.wallpaper.model.Remix
import dev.paperouette.wallpaper.model.RotationDirection
import dev.paperouette.wallpaper.model.SubsetLayout
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max

data class Viewport(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }

    val aspectRatio: Float get() = width.toFloat() / height
}

data class MotionState(
    val launcherX: Float = 0f,
    val launcherY: Float = 0f,
    /**
     * How far the launcher has zoomed away from the home screen, 0 at rest and 1 at the app drawer
     * or the recents view. It arrives through the same door as the launcher's pan because it is the
     * same kind of thing — a value the window manager pushes at whatever rate it likes, which the
     * renderer should read once per frame rather than chase.
     */
    val launcherZoom: Float = 0f,
    val dragX: Float = 0f,
    val dragY: Float = 0f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val spinRadians: Float = 0f,
)

internal data class MotionSample(
    val state: MotionState,
    val highMotion: Boolean,
)

data class FilterState(
    val dim: Float = 0f,
    val grayscale: Float = 0f,
    val blur: Float = 0f,
) {
    fun normalized() = FilterState(
        dim = dim.coerceIn(0f, 1f),
        grayscale = grayscale.coerceIn(0f, 1f),
        blur = blur.coerceIn(0f, 1f),
    )
}

/**
 * Where on the **screen** the artwork turns, as a fraction of the viewport from its top left.
 *
 * The obvious design puts the artwork's centre in the middle of the screen and spins it there.
 * This moves that point: at (0.25, 0.75) the middle of the artwork sits a quarter across and three
 * quarters down, and the scene turns about that spot on the glass.
 *
 * It costs framing, because the far corner of the screen is then further from the centre of
 * rotation and sweeps a wider circle — bounded, though: the worst case, a corner of the screen, is
 * twice the reach of the middle.
 */
data class RotationCenter(val x: Float = 0.5f, val y: Float = 0.5f) {
    fun sanitized() = RotationCenter(
        x = x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f,
        y = y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f,
    )

    companion object {
        val Center = RotationCenter()
    }
}

data class RenderFrameState(
    val scene: Remix,
    val filters: FilterState = FilterState(),
    val rotationCenter: RotationCenter = RotationCenter.Center,
    val mirrored: Boolean = false,
    val rotationReversed: Boolean = false,
    val animationPaused: Boolean = false,
    /** How fast the scene runs its own catalogued animation; 0 holds it still. */
    val animationSpeed: Float = DEFAULT_ANIMATION_SPEED,
)

const val DEFAULT_ANIMATION_SPEED = 1f
const val MAX_ANIMATION_SPEED = 3f

/** Maintains an animation timeline that does not advance while rendering is stopped. */
internal class AnimationClock {
    private var animationNanos = 0L
    private var lastTickNanos = 0L

    /**
     * Advances the timeline by the time since the last tick, scaled by [speed].
     *
     * Scaling here rather than in `LayerTransform` keeps the timeline monotonic, so every layer
     * stays registered with every other and the catalogued rotation periods still divide it evenly.
     * The user's own spin is untouched — that is `MotionController`'s, and its wrap period is what
     * `MotionMath.SPIN_WRAP_TURNS` is checked against.
     */
    fun sample(
        monotonicNanos: Long,
        running: Boolean,
        speed: Float = DEFAULT_ANIMATION_SPEED,
    ): Long {
        if (!running) {
            lastTickNanos = 0L
            return animationNanos
        }
        if (lastTickNanos != 0L) {
            val elapsed = max(0L, monotonicNanos - lastTickNanos)
            animationNanos += (elapsed * boundedSpeed(speed)).toLong()
        }
        lastTickNanos = monotonicNanos
        return animationNanos
    }

    fun resetBaseline() {
        lastTickNanos = 0L
    }

    private fun boundedSpeed(speed: Float): Float =
        speed.takeIf(Float::isFinite)?.coerceIn(0f, MAX_ANIMATION_SPEED) ?: DEFAULT_ANIMATION_SPEED
}

/** Produces absolute deadlines so delayed frames do not permanently shift the cadence. */
internal class FramePacer {
    private var nextDeadlineNanos = 0L
    private var lastPeriodNanos = 0L

    fun nextDeadline(monotonicNanos: Long, highMotion: Boolean): Long {
        val period = if (highMotion) INTERACTIVE_PERIOD_NANOS else IDLE_PERIOD_NANOS
        if (nextDeadlineNanos == 0L || period != lastPeriodNanos) {
            nextDeadlineNanos = monotonicNanos + period
        } else {
            do {
                nextDeadlineNanos += period
            } while (nextDeadlineNanos <= monotonicNanos)
        }
        lastPeriodNanos = period
        return nextDeadlineNanos
    }

    fun reset() {
        nextDeadlineNanos = 0L
        lastPeriodNanos = 0L
    }

    companion object {
        const val INTERACTIVE_FPS = 60f
        const val IDLE_FPS = 30f
        const val INTERACTIVE_PERIOD_NANOS = 1_000_000_000L / 60L
        const val IDLE_PERIOD_NANOS = 1_000_000_000L / 30L
    }
}

/**
 * Turns a layer's colour ramp into the texture the shader looks its mask up in.
 *
 * Premultiplied, because the renderer blends with `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` and Android's
 * own bitmaps arrive premultiplied — so a ramp built any other way would fringe against everything
 * drawn beside it. Premultiplying before interpolation is also the only way the midpoint between
 * an opaque colour and a transparent one stays that colour rather than fading through it.
 */
internal object RampTexture {
    const val WIDTH = 256

    /** RGBA8 bytes for a [WIDTH]×1 texture, ready for `glTexImage2D`. */
    fun build(stops: List<RampStop>): ByteArray {
        require(stops.isNotEmpty()) { "A ramp needs at least one stop" }
        val ordered = stops.sortedBy(RampStop::at)
        val bytes = ByteArray(WIDTH * 4)
        for (index in 0 until WIDTH) {
            val position = index.toFloat() / (WIDTH - 1)
            val upper = ordered.indexOfFirst { it.at >= position }.takeIf { it >= 0 } ?: ordered.size
            val after = ordered.getOrNull(upper) ?: ordered.last()
            val before = ordered.getOrNull(upper - 1) ?: ordered.first()
            val span = after.at - before.at
            val blend = if (span <= 0f) 0f else ((position - before.at) / span).coerceIn(0f, 1f)
            writePremultiplied(bytes, index * 4, before.color, after.color, blend)
        }
        return bytes
    }

    /**
     * The ramp's average colour, premultiplied, as `[r, g, b, a]` in 0..1.
     *
     * What a cyclic layer settles on where the cycle runs faster than the screen can show it. The
     * stored `(cos, sin)` pair shrinks toward zero as the filtering averages a whole turn, and at
     * that point no single position on the ramp is the honest answer — the mean is.
     */
    fun mean(stops: List<RampStop>): FloatArray {
        val bytes = build(stops)
        val total = FloatArray(4)
        for (index in 0 until WIDTH) {
            for (channel in 0 until 4) {
                total[channel] += (bytes[index * 4 + channel].toInt() and 0xff) / 255f
            }
        }
        return FloatArray(4) { total[it] / WIDTH }
    }

    private fun writePremultiplied(target: ByteArray, offset: Int, from: Int, to: Int, blend: Float) {
        fun channel(shift: Int): Float {
            val start = (from ushr shift and 0xff) / 255f
            val end = (to ushr shift and 0xff) / 255f
            return start + (end - start) * blend
        }
        val alpha = channel(24)
        fun byte(value: Float): Byte = (value * 255f).toInt().coerceIn(0, 255).toByte()
        target[offset] = byte(channel(16) * alpha)
        target[offset + 1] = byte(channel(8) * alpha)
        target[offset + 2] = byte(channel(0) * alpha)
        target[offset + 3] = byte(alpha)
    }
}

internal object PaletteTransform {
    fun apply(color: Int, filters: FilterState): Int {
        val normalized = filters.normalized()
        val alpha = color ushr 24 and 0xff
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
        val brightness = 1f - normalized.dim
        fun channel(value: Int): Int = (
            (value + (luminance - value) * normalized.grayscale) * brightness
        ).toInt().coerceIn(0, 255)
        return (alpha shl 24) or
            (channel(red) shl 16) or
            (channel(green) shl 8) or
            channel(blue)
    }
}

internal data class WallpaperPalette(
    val primary: Int,
    val secondary: Int?,
    val tertiary: Int?,
    /** Whether the system should draw the status bar's clock and icons dark rather than light. */
    val suitsDarkText: Boolean,
)

internal object WallpaperPaletteTransform {
    /**
     * The colours the wallpaper reports to the system.
     *
     * [primary] is the artwork's measured [dominant] tone, not the palette's headline colour.
     * `WallpaperColors.fromBitmap` returns the most *populous* colour first, so that is what every
     * reader expects primary to mean — and readers do vary: the lock screen goes by the hints,
     * while Smart Launcher goes by the primary colour and picked black icons for a black wallpaper
     * because we were offering it a bright teal accent. The accent is still reported, one place
     * down, where its meaning is not load-bearing.
     */
    fun apply(colors: PaletteColors, filters: FilterState, dominant: Int): WallpaperPalette {
        val primary = PaletteTransform.apply(dominant, filters)
        val accents = listOfNotNull(
            colors.vibrantColor,
            colors.mutedColor,
            colors.darkVibrantColor,
        )
            .map { color -> PaletteTransform.apply(color, filters) }
            .distinct()
            .filterNot { it == primary }
        return WallpaperPalette(
            primary = primary,
            secondary = accents.getOrNull(0),
            tertiary = accents.getOrNull(1),
            // Through the filters too: dimming the wallpaper really does darken it, and a dim
            // wallpaper wants light icons whatever the artwork underneath is doing.
            suitsDarkText = SceneTone.suitsDarkText(primary),
        )
    }
}

object LayerTransform {
    fun rotationRadians(
        layer: Layer,
        monotonicNanos: Long,
        reversed: Boolean = false,
    ): Float {
        val rotation = layer.rotation ?: return 0f
        if (rotation.time <= 0f) return 0f
        val seconds = monotonicNanos / 1_000_000_000.0
        val turns = (seconds % rotation.time) / rotation.time
        val clockwise = rotation.direction == RotationDirection.CLOCKWISE
        val sign = if (clockwise != reversed) 1f else -1f
        return (turns * PI * 2.0).toFloat() * sign
    }

    /**
     * Total rotation for a layer, including the user's spin.
     *
     * Layers the catalogue does not rotate stay still: a scene's backdrop is a full-bleed opaque
     * image, and turning it would swing its rectangular edges into view.
     */
    fun totalRotationRadians(
        layer: Layer,
        monotonicNanos: Long,
        spinRadians: Float,
        inputRotationScaler: Float,
        reversed: Boolean = false,
    ): Float {
        if (layer.rotation == null) return 0f
        // [reversed] turns the scene's own animation around, and only that. The user's spin keeps
        // its sign, so a flick or a drag still pushes the artwork the way the hand went.
        return rotationRadians(layer, monotonicNanos, reversed) +
            spinRadians * inputRotationScaler
    }

    fun parallax(layer: Layer, motion: MotionState): Pair<Float, Float> {
        val x = if (layer.parallaxOnlyOnTilt) {
            motion.tiltX
        } else {
            motion.launcherX + motion.dragX + motion.tiltX
        }
        val y = if (layer.parallaxOnlyOnTilt) {
            motion.tiltY
        } else {
            motion.launcherY + motion.dragY + motion.tiltY
        }
        val scale = layer.parallaxScale * PARALLAX_UV_SCALE
        // Launcher, drag and tilt each contribute up to a unit, and tilt sensitivity can double
        // its share. Bounding the sum keeps the pan inside the headroom SceneCoverage reserves.
        return x.coerceIn(-1f, 1f) * scale to y.coerceIn(-1f, 1f) * scale
    }

    /** Largest offset [parallax] can return for [layer], in scene units. */
    fun maxParallaxOffset(layer: Layer): Float = layer.parallaxScale * PARALLAX_UV_SCALE

    private const val PARALLAX_UV_SCALE = 0.08f
}

/**
 * Keeps a rotating scene covering the screen.
 *
 * The artwork is square and is sampled through a viewport-shaped window centred on the rotation
 * centre. Rotating that window sweeps its corners around a circle, so unless the window is shrunk
 * the corners leave the image and the renderer draws nothing there. The shrink depends on the
 * screen's shape, not its resolution — the squarer the screen, the further out its corners reach —
 * and on where the rotation centre sits, since the safe circle is bounded by the nearest edge.
 */
internal object SceneCoverage {
    /**
     * The portion of the artwork a viewport shows, in scene units, and how far it may still pan.
     *
     * [panAllowance] is what the framing actually left for parallax, which is not always the
     * nominal amount: a rotation centre close to an edge leaves a small safe circle, and panning
     * past what was reserved would put a corner outside the artwork.
     */
    data class Window(val x: Float, val y: Float, val panAllowance: Float)

    /**
     * Cover-fits [sceneAspect] into [viewportAspect], then shrinks the result so the window stays
     * inside the base layer whatever it does around [pivot].
     *
     * A static base layer with a centred pivot needs no shrink at all and is framed by cover-fit
     * alone, exactly as before this setting existed. Move the pivot, though, and even a static
     * layer has to be fitted: the window is centred on the pivot, so off-centre it would otherwise
     * hang over an edge.
     */
    fun window(
        scene: Remix,
        sceneAspect: Float,
        viewportAspect: Float,
        pivot: RotationCenter,
    ): Window {
        val visibleX: Float
        val visibleY: Float
        if (sceneAspect > viewportAspect) {
            visibleX = viewportAspect / sceneAspect
            visibleY = 1f
        } else {
            visibleX = 1f
            visibleY = sceneAspect / viewportAspect
        }
        val base = scene.layers.first()
        val nominalPan = LayerTransform.maxParallaxOffset(base)
        if (base.rotation == null && pivot == RotationCenter.Center) {
            return Window(visibleX, visibleY, nominalPan)
        }
        return fit(
            visibleX = visibleX,
            visibleY = visibleY,
            subset = base.imageSubsetLayoutParams ?: SubsetLayout.Full,
            parallaxReserve = nominalPan,
            pivot = pivot,
        )
    }

    /**
     * Closes [window] in by the launcher's [zoom], showing less of the artwork and so drawing it
     * larger as the app drawer opens.
     *
     * Always inwards, which is why this needs no refitting. [fit] has already left the window's
     * corners inside the artwork at every angle, and reach is linear in the window's span, so
     * scaling the span by anything at or below 1 scales the reach by the same amount and leaves the
     * guarantee with room to spare. The pan allowance carries over untouched for the same reason —
     * it was granted against the larger window.
     */
    fun zoomed(window: Window, zoom: Float): Window {
        val bounded = zoom.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        if (bounded == 0f) return window
        val scale = 1f - bounded * ZOOM_DEPTH
        return window.copy(x = window.x * scale, y = window.y * scale)
    }

    /**
     * How far in the artwork closes when the launcher is fully zoomed away. Small on purpose: this
     * is depth behind an app drawer, not a transition of its own.
     */
    const val ZOOM_DEPTH = 0.06f

    /**
     * Shrinks a `(visibleX, visibleY)` window until it stays inside [subset] at every rotation
     * about [pivot], and reports how much pan headroom that left.
     *
     * Scale and allowance come from here together on purpose. Panning is capped so it cannot claim
     * more than half the safe radius, so when [pivot] sits near an edge the headroom granted is
     * less than [parallaxReserve] asked for — and a caller that panned by the nominal amount anyway
     * would swing a corner off the artwork.
     */
    fun fit(
        visibleX: Float,
        visibleY: Float,
        subset: SubsetLayout,
        parallaxReserve: Float,
        pivot: RotationCenter = RotationCenter.Center,
    ): Window {
        val inscribed = safeRadius(subset)
        val reach = reach(visibleX, visibleY, pivot)
        if (inscribed <= 0f || reach <= 0f) return Window(visibleX, visibleY, parallaxReserve)
        val usable = (inscribed - parallaxReserve).coerceAtLeast(inscribed * 0.5f)
        val scale = (usable / reach).coerceAtMost(1f)
        return Window(
            x = visibleX * scale,
            y = visibleY * scale,
            panAllowance = (inscribed - scale * reach).coerceIn(0f, parallaxReserve),
        )
    }

    /**
     * How far the furthest corner of the screen sits from the centre of rotation, in scene units
     * before scaling.
     *
     * The screen turns about [pivot], so it is the corner diagonally opposite that sweeps the
     * widest circle. In the middle every corner is half a screen away and this is the familiar
     * half-diagonal; at a corner of the screen the opposite corner is a full screen away, which is
     * the worst case and exactly twice the reach.
     */
    private fun reach(visibleX: Float, visibleY: Float, pivot: RotationCenter): Float = hypot(
        max(pivot.x, 1f - pivot.x) * visibleX,
        max(pivot.y, 1f - pivot.y) * visibleY,
    )

    /** Largest circle around the middle of the artwork that stays inside [subset]. */
    private fun safeRadius(subset: SubsetLayout): Float = minOf(
        0.5f - subset.xRatio,
        subset.xRatio + subset.sceneWidthRatio - 0.5f,
        0.5f - subset.yRatio,
        subset.yRatio + subset.sceneHeightRatio - 0.5f,
    )
}
