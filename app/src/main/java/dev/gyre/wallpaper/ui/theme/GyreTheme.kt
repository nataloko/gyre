package dev.gyre.wallpaper.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.gyre.wallpaper.model.PaletteColors

@Composable
fun GyreTheme(palette: PaletteColors? = null, content: @Composable () -> Unit) {
    val target = remember(palette) {
        ArtworkColorScheme.chassis(palette ?: FALLBACK_PALETTE)
    }
    MaterialTheme(
        colorScheme = target.animated(),
        shapes = GyreShapes,
        typography = GyreTypography,
        content = content,
    )
}

/**
 * Eases the accent roles toward the new artwork's colours, so moving through the collection washes
 * the controls from one palette to the next instead of snapping.
 *
 * Only the accents: the chassis tones are fixed, so animating them would spend a frame callback per
 * role to arrive back where it started.
 */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun Color.eased(label: String) =
        animateColorAsState(this, GyreMotion.colorWash(), label = label).value

    return copy(
        primary = primary.eased("primary"),
        onPrimary = onPrimary.eased("onPrimary"),
        primaryContainer = primaryContainer.eased("primaryContainer"),
        onPrimaryContainer = onPrimaryContainer.eased("onPrimaryContainer"),
        secondary = secondary.eased("secondary"),
        onSecondary = onSecondary.eased("onSecondary"),
        secondaryContainer = secondaryContainer.eased("secondaryContainer"),
        onSecondaryContainer = onSecondaryContainer.eased("onSecondaryContainer"),
        tertiary = tertiary.eased("tertiary"),
        onTertiary = onTertiary.eased("onTertiary"),
        tertiaryContainer = tertiaryContainer.eased("tertiaryContainer"),
        onTertiaryContainer = onTertiaryContainer.eased("onTertiaryContainer"),
        surfaceTint = surfaceTint.eased("surfaceTint"),
    )
}

/** Used before a scene is known, and by previews. */
private val FALLBACK_PALETTE = PaletteColors(
    loadingColor = FALLBACK_SEED,
    vibrantColor = FALLBACK_SEED,
)

private const val FALLBACK_SEED = 0xFF00A6B8.toInt()
