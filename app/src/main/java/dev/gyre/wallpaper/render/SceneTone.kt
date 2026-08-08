package dev.gyre.wallpaper.render

import android.content.Context
import android.graphics.ImageDecoder
import dev.gyre.wallpaper.data.ArtworkPalette
import dev.gyre.wallpaper.data.ArtworkPaths
import dev.gyre.wallpaper.data.decoderSource
import dev.gyre.wallpaper.model.Remix
import kotlin.math.pow

/**
 * How bright a scene actually is, measured from its own artwork.
 *
 * The system decides whether to draw the status bar's clock and icons dark or light from the
 * `WallpaperColors` the wallpaper reports. The catalogue's palette cannot answer that on its own:
 * its `vibrantColor` is the artwork's most characteristic colour, not its most common one, so a
 * piece that is nearly all black — a Strange Loop mandala on a near-black field, say — advertises
 * a bright accent and the system would draw black icons onto black.
 *
 * So the tone is measured instead, from the thumb, which is the whole scene composited and already
 * bundled. Averaged over a handful of samples and cached per remix, since it never changes.
 */
internal class SceneTone(private val context: Context) {
    private val measured = HashMap<String, Int>()

    @Synchronized
    fun dominantColor(remix: Remix): Int = measured.getOrPut(remix.id) {
        // A missing or unreadable thumb falls back to the catalogue's own background colour, which
        // is a far better guess than the vibrant accent even when it is not exact.
        runCatching { measure(remix) }.getOrDefault(remix.colors.loadingColor)
    }

    private fun measure(remix: Remix): Int {
        val source = ArtworkPaths.resolve(remix.previews.thumb, context)
        val bitmap = ImageDecoder.decodeBitmap(
            source.decoderSource(context),
        ) { decoder, _, _ ->
            decoder.setTargetSize(SAMPLE_EDGE, SAMPLE_EDGE)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        try {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
        // Shared with the importer, which measures an imported photo's loading colour the same
        // way. One definition of "the tone of this artwork", or a piece would load behind one
        // colour and report another.
        return ArtworkPalette.meanColor(pixels)
    }

    companion object {
        /**
         * Whether dark icons read better than light ones on [color].
         *
         * Black beats white exactly where their WCAG contrasts meet: `(L + 0.05)² = 1.05 × 0.05`,
         * so at a relative luminance of about 0.179. Below that the status bar wants light icons.
         */
        fun suitsDarkText(color: Int): Boolean = relativeLuminance(color) > DARK_TEXT_CROSSOVER

        /**
         * WCAG relative luminance, which gamma-expands each channel first.
         *
         * Deliberately not `PaletteTransform`'s raw-channel luminance: that one drives greyscale
         * mixing, where working on the values the shader already has is the point. This one is
         * deciding legibility, which is perceptual.
         */
        private fun relativeLuminance(color: Int): Double {
            fun expand(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * expand(color shr 16 and 0xff) +
                0.7152 * expand(color shr 8 and 0xff) +
                0.0722 * expand(color and 0xff)
        }

        private const val DARK_TEXT_CROSSOVER = 0.179
        private const val SAMPLE_EDGE = 8
    }
}
