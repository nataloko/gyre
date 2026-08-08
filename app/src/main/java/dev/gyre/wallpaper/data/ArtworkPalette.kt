package dev.gyre.wallpaper.data

import dev.gyre.wallpaper.model.PaletteColors
import dev.gyre.wallpaper.ui.theme.ArtworkColorScheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The palette a piece of imported artwork dresses the interface in.
 *
 * The bundled catalogue ships one per remix, measured by the generator from the render it just
 * made. An imported photo arrives with nothing, so the same questions are answered here from its
 * thumb: what colour sits behind it while it loads, what its accent is, and whether it is dark.
 *
 * Deliberately plain arithmetic over an `IntArray` rather than anything from `android.graphics`,
 * so the whole of it is a JVM unit test rather than a device one.
 */
object ArtworkPalette {
    /**
     * The average colour, which is what sits behind a tile before its artwork decodes.
     *
     * The mean rather than the most populous bucket: it is what `render/SceneTone` already
     * measures for the wallpaper's reported tone, and the two must agree or a piece would load
     * behind one colour and report another.
     */
    fun meanColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return OPAQUE_BLACK
        var red = 0L
        var green = 0L
        var blue = 0L
        pixels.forEach { pixel ->
            red += pixel shr 16 and 0xff
            green += pixel shr 8 and 0xff
            blue += pixel and 0xff
        }
        val count = pixels.size
        return argb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    /**
     * Whether [color] is dark enough that the system should draw light icons over it.
     *
     * The same 0.179 crossover the generator uses as `DARK_TONE_CROSSOVER`, so an imported piece
     * and a bundled one mean the same thing by `isDark`.
     */
    fun isDark(color: Int): Boolean = relativeLuminance(color) <= DARK_TONE_CROSSOVER

    /**
     * A palette from [pixels], with every accent already lifted to where the chrome can read it.
     *
     * The lift is not optional. `ArtworkColorScheme` picks foregrounds by measured contrast but
     * seeds them from whatever it is given, and the generator clears every bundled seed before it
     * renders; an unlifted import would be the first artwork in the app to dress a chip in
     * something illegible.
     */
    fun paletteFor(pixels: IntArray): PaletteColors {
        val mean = meanColor(pixels)
        val buckets = buckets(pixels)
        val coloured = buckets.filter { it.saturation >= MIN_SATURATION }
        // A greyscale photo gets an explicit neutral rather than no accent at all: with
        // vibrantColor null the chrome seeds from loadingColor, and hsl() reports hue 0 for a
        // grey, whose saturation is then floored — which would dress the whole interface in red.
        if (coloured.isEmpty()) {
            return PaletteColors(
                loadingColor = mean,
                vibrantColor = NEUTRAL_ACCENT,
                mutedColor = NEUTRAL_ACCENT,
            )
        }
        val vibrant = coloured.maxBy { it.saturation * sqrt(it.weight.toDouble()) }
        val light = coloured.filter { it.lightness > 0.6f }.maxByOrNull { it.saturation }
        val darkest = coloured.minBy { it.lightness }
        return PaletteColors(
            loadingColor = mean,
            vibrantColor = seed(vibrant.color),
            darkVibrantColor = seed(vibrant.color),
            lightVibrantColor = seed((light ?: vibrant).color),
            mutedColor = seed(desaturated(vibrant.color)),
            darkMutedColor = seed(desaturated(darkest.color)),
        )
    }

    private fun seed(argb: Int) = ArtworkColorScheme.legibleSeed(argb)

    private class Bucket(val color: Int, val weight: Int) {
        val saturation: Float
        val lightness: Float

        init {
            val red = (color shr 16 and 0xff) / 255f
            val green = (color shr 8 and 0xff) / 255f
            val blue = (color and 0xff) / 255f
            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            lightness = (maximum + minimum) / 2f
            val delta = maximum - minimum
            saturation = if (delta < 1e-5f) {
                0f
            } else {
                delta / max(1e-5f, 1f - abs(2f * lightness - 1f))
            }
        }
    }

    /**
     * [pixels] quantised to a 5-5-5 grid, so near-identical shades count together.
     *
     * Near-black and near-white are dropped first: they are the background of half the photographs
     * ever taken and would win every count, while telling you nothing about the artwork's colour.
     */
    private fun buckets(pixels: IntArray): List<Bucket> {
        val counts = HashMap<Int, IntArray>()
        pixels.forEach { pixel ->
            val red = pixel shr 16 and 0xff
            val green = pixel shr 8 and 0xff
            val blue = pixel and 0xff
            val lightness = (max(red, max(green, blue)) + min(red, min(green, blue))) / 510f
            if (lightness < MIN_LIGHTNESS || lightness > MAX_LIGHTNESS) return@forEach
            val key = (red shr 3 shl 10) or (green shr 3 shl 5) or (blue shr 3)
            val totals = counts.getOrPut(key) { IntArray(4) }
            totals[0] += red
            totals[1] += green
            totals[2] += blue
            totals[3]++
        }
        return counts.values.map { totals ->
            val count = totals[3]
            Bucket(argb(totals[0] / count, totals[1] / count, totals[2] / count), count)
        }
    }

    private fun desaturated(argb: Int): Int {
        val red = argb shr 16 and 0xff
        val green = argb shr 8 and 0xff
        val blue = argb and 0xff
        val grey = (red + green + blue) / 3
        fun mix(channel: Int) = (grey + (channel - grey) * MUTED_SATURATION).toInt().coerceIn(0, 255)
        return argb(mix(red), mix(green), mix(blue))
    }

    private fun relativeLuminance(color: Int): Double {
        fun expand(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * expand(color shr 16 and 0xff) +
            0.7152 * expand(color shr 8 and 0xff) +
            0.0722 * expand(color and 0xff)
    }

    private fun argb(red: Int, green: Int, blue: Int) =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    /** Matches the generator's DARK_TONE_CROSSOVER, and SceneTone's own legibility crossover. */
    const val DARK_TONE_CROSSOVER = 0.179

    private const val OPAQUE_BLACK = 0xff shl 24
    private const val MIN_SATURATION = 0.15f
    private const val MIN_LIGHTNESS = 0.08f
    private const val MAX_LIGHTNESS = 0.95f
    private const val MUTED_SATURATION = 0.45f

    /** The chrome's own on-surface tone, for artwork that has no colour to offer. */
    private const val NEUTRAL_ACCENT = 0xFFC8C8D2.toInt()
}
