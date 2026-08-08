package dev.paperouette.wallpaper.render

import dev.paperouette.wallpaper.model.PaletteColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaletteTransformTest {
    @Test
    fun dimAndGrayscaleTransformReportedColors() {
        val source = 0xffcc6020.toInt()
        val grayscale = PaletteTransform.apply(source, FilterState(grayscale = 1f))
        val red = grayscale ushr 16 and 0xff
        val green = grayscale ushr 8 and 0xff
        val blue = grayscale and 0xff
        assertTrue(abs(red - green) <= 1)
        assertTrue(abs(green - blue) <= 1)

        val dimmed = PaletteTransform.apply(source, FilterState(dim = 0.5f))
        assertTrue((dimmed ushr 16 and 0xff) < (source ushr 16 and 0xff))
        assertEquals(0xff, dimmed ushr 24)
    }

    @Test
    fun darkAccentBecomesSecondaryWhenMutedColorIsMissing() {
        val darkAccent = 0xff123456.toInt()
        val palette = WallpaperPaletteTransform.apply(
            PaletteColors(
                loadingColor = 0xffabcdef.toInt(),
                mutedColor = null,
                darkVibrantColor = darkAccent,
            ),
            FilterState(),
            dominant = 0xffabcdef.toInt(),
        )

        assertEquals(darkAccent, palette.secondary)
        assertNull(palette.tertiary)
    }

    @Test
    fun duplicateAccentsDoNotCreateAnInvalidTertiaryColor() {
        val accent = 0xff123456.toInt()
        val palette = WallpaperPaletteTransform.apply(
            PaletteColors(
                loadingColor = 0xffabcdef.toInt(),
                mutedColor = accent,
                darkVibrantColor = accent,
            ),
            FilterState(),
            dominant = 0xffabcdef.toInt(),
        )

        assertEquals(accent, palette.secondary)
        assertNull(palette.tertiary)
    }
}
