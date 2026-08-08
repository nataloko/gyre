package dev.gyre.wallpaper.data

import androidx.compose.ui.graphics.Color
import dev.gyre.wallpaper.ui.theme.ArtworkColorScheme
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette an imported photo dresses the interface in.
 *
 * The last test is the one that matters: `ArtworkColorSchemeTest` proves the chrome stays legible
 * over every *bundled* variant, and every one of those was pre-cleared by the generator. Artwork
 * the user imports gets its clearance here instead, so this is the same contract held over
 * arbitrary input.
 */
class ArtworkPaletteTest {
    @Test
    fun theMeanIsTheAverageOfEveryPixel() {
        val pixels = intArrayOf(argb(0, 0, 0), argb(255, 255, 255), argb(100, 150, 200))

        assertEquals(argb(118, 135, 151), ArtworkPalette.meanColor(pixels))
    }

    @Test
    fun anEmptyImageIsBlackRatherThanACrash() {
        assertEquals(argb(0, 0, 0), ArtworkPalette.meanColor(IntArray(0)))
    }

    /** The crossover where black text and white text have equal WCAG contrast. */
    @Test
    fun darknessIsMeasuredAtTheSameCrossoverAsTheWallpapersOwnTone() {
        assertTrue(ArtworkPalette.isDark(argb(0, 0, 0)))
        assertTrue(!ArtworkPalette.isDark(argb(255, 255, 255)))
        // Whatever the mid-grey lands on, the two definitions must agree on it.
        listOf(0, 40, 80, 120, 160, 200, 255).forEach { level ->
            val colour = argb(level, level, level)
            assertEquals(
                "grey $level",
                ArtworkPalette.isDark(colour),
                !dev.gyre.wallpaper.render.SceneTone.suitsDarkText(colour),
            )
        }
    }

    @Test
    fun theAccentComesFromTheColourThatDominatesRatherThanTheBackdrop() {
        // A mostly-black field with a strong orange in it: the accent is the orange.
        val pixels = IntArray(1000) { if (it < 200) argb(230, 120, 20) else argb(4, 4, 6) }

        val palette = ArtworkPalette.paletteFor(pixels)
        val vibrant = requireNotNull(palette.vibrantColor)
        assertTrue("red should lead: ${vibrant.toUInt().toString(16)}", red(vibrant) > blue(vibrant))
        assertEquals(ArtworkPalette.meanColor(pixels), palette.loadingColor)
    }

    /**
     * A black-and-white photograph must not dress the interface in red.
     *
     * With no accent at all the chrome seeds from `loadingColor`, and the colour maths reports hue
     * 0 for a grey and then floors its saturation — so a greyscale import would come out scarlet.
     */
    @Test
    fun aGreyscaleImageTakesANeutralAccentRatherThanHueZero() {
        val pixels = IntArray(400) { argb(it % 200 + 30, it % 200 + 30, it % 200 + 30) }

        val palette = ArtworkPalette.paletteFor(pixels)
        val vibrant = requireNotNull(palette.vibrantColor)
        assertEquals(red(vibrant), green(vibrant))
        assertTrue(kotlin.math.abs(green(vibrant) - blue(vibrant)) <= 16)
    }

    @Test
    fun everyPaletteIsCompleteEnoughForTheChromeToSeedFrom() {
        val palette = ArtworkPalette.paletteFor(IntArray(64) { argb(20, 60, 200) })

        assertNotNull(palette.vibrantColor)
        assertNotNull(palette.mutedColor)
    }

    /**
     * Over a few hundred pseudo-random images, the chrome the palette produces stays legible.
     *
     * Saturated blue is the case that bites: it clears the near-black background at a lightness
     * the container surfaces then fail, which is exactly what the seed lift exists for.
     */
    @Test
    fun everySynthesisedPaletteProducesLegibleChrome() {
        val random = Random(20260808)
        repeat(300) { round ->
            val pixels = when (round % 4) {
                // A flat colour, a two-tone field, a saturated blue, and noise.
                0 -> IntArray(256) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
                1 -> IntArray(256) { if (it % 3 == 0) argb(10, 10, 14) else argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
                2 -> IntArray(256) { argb(random.nextInt(40), random.nextInt(60), 180 + random.nextInt(70)) }
                else -> IntArray(256) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            }
            val palette = ArtworkPalette.paletteFor(pixels)
            val scheme = ArtworkColorScheme.chassis(palette)

            listOf(
                "primary" to (scheme.primary to scheme.onPrimary),
                "secondary" to (scheme.secondary to scheme.onSecondary),
                "tertiary" to (scheme.tertiary to scheme.onTertiary),
                "primaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
            ).forEach { (name, pair) ->
                val (background, foreground) = pair
                assertTrue(
                    "round $round $name foreground",
                    ArtworkColorScheme.contrast(foreground, background) >=
                        ArtworkColorScheme.MIN_CONTRAST,
                )
            }
            // The accents have to stand out from the surface they are drawn on, which is the
            // container tone rather than the background — the case the seed lift was written for.
            assertTrue(
                "round $round accent on container",
                ArtworkColorScheme.contrast(scheme.primary, scheme.surfaceContainerHighest) >=
                    ArtworkColorScheme.MIN_ACCENT_CONTRAST,
            )
        }
    }

    private fun argb(red: Int, green: Int, blue: Int) =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    private fun red(argb: Int) = argb shr 16 and 0xff
    private fun green(argb: Int) = argb shr 8 and 0xff
    private fun blue(argb: Int) = argb and 0xff

    private operator fun Pair<Color, Color>.component1() = first
    private operator fun Pair<Color, Color>.component2() = second
}
