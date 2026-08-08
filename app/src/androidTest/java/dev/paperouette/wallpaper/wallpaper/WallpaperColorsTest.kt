package dev.paperouette.wallpaper.wallpaper

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperouette.wallpaper.data.BundledCatalogRepository
import dev.paperouette.wallpaper.render.FilterState
import dev.paperouette.wallpaper.render.SceneTone
import dev.paperouette.wallpaper.render.WallpaperPaletteTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperColorsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val catalogue = BundledCatalogRepository(context).current.value
    private val tone = SceneTone(context)

    @Test
    fun everyRemixCreatesValidWallpaperColors() {
        catalogue.remixes.forEach { remix ->
            val palette = WallpaperPaletteTransform.apply(
                remix.colors,
                FilterState(),
                tone.dominantColor(remix),
            )
            WallpaperColors(
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
    }

    /**
     * The status bar's clock and icons are drawn from this, so it has to describe the artwork
     * rather than the palette's headline colour. A piece that is almost entirely black must ask for
     * light icons however bright its accent is — Spokes advertises a near-white yellow, and asking
     * for dark text on the strength of it painted black icons onto black.
     */
    @Test
    fun scenesThatLookDarkAskForLightIcons() {
        val wrong = catalogue.remixes.filter { remix ->
            val dominant = tone.dominantColor(remix)
            val palette = WallpaperPaletteTransform.apply(remix.colors, FilterState(), dominant)
            palette.suitsDarkText != SceneTone.suitsDarkText(dominant)
        }

        assertTrue("hint disagrees with the artwork for ${wrong.map { it.id }.take(5)}", wrong.isEmpty())
    }

    /** Dimming the wallpaper really does darken it, whatever the artwork underneath is doing. */
    @Test
    fun aFullyDimmedWallpaperAlwaysAsksForLightIcons() {
        val stillDark = catalogue.remixes.filter { remix ->
            WallpaperPaletteTransform.apply(
                remix.colors,
                FilterState(dim = 1f),
                tone.dominantColor(remix),
            ).suitsDarkText
        }

        assertTrue("dimmed to black but still asking for dark text: $stillDark", stillDark.isEmpty())
    }

    /** Measuring is not free, so it happens once per remix and is remembered. */
    @Test
    fun theMeasuredToneIsStable() {
        val remix = catalogue.remix("reactor_hue_coral")

        assertEquals(tone.dominantColor(remix), tone.dominantColor(remix))
    }
}
