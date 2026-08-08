package dev.paperouette.wallpaper.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.paperouette.wallpaper.model.Catalog
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interface takes its colours from whatever artwork is on screen, so legibility cannot be
 * checked by eye on a handful of designs — it has to hold for every remix in both themes.
 */
class ArtworkColorSchemeTest {
    /**
     * The chassis is what the interface is drawn in: neutral surfaces that hold still, accents
     * taken from the artwork. It is the only scheme there is — an earlier one that tinted every
     * surface went when the interface became live-first, and this covers what replaced it: every
     * foreground pair, and every accent against the surface it lands on.
     */
    @Test
    fun everyRemixProducesLegibleChromeOnTheChassis() {
        val failures = mutableListOf<String>()

        for (remix in catalogue().remixes) {
            val scheme = ArtworkColorScheme.chassis(remix.colors)
            for ((role, foreground, background) in scheme.foregroundPairs()) {
                val ratio = ArtworkColorScheme.contrast(foreground, background)
                if (ratio < ArtworkColorScheme.MIN_CONTRAST) {
                    failures += "${remix.id} $role: %.2f:1".format(ratio)
                }
            }
            // Every accent lands on a chassis surface somewhere: the primary on a filled button,
            // the others on a fader track or a chip border.
            for ((role, accent) in listOf(
                "primary" to scheme.primary,
                "secondary" to scheme.secondary,
                "tertiary" to scheme.tertiary,
            )) {
                val ratio = ArtworkColorScheme.contrast(accent, scheme.surfaceContainerHighest)
                if (ratio < ArtworkColorScheme.MIN_ACCENT_CONTRAST) {
                    failures += "${remix.id} $role/surfaceContainerHighest: %.2f:1".format(ratio)
                }
            }
        }

        assertTrue("chassis chrome falls below contrast — ${failures.take(8)}", failures.isEmpty())
    }

    private fun ColorScheme.foregroundPairs(): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary/primary", onPrimary, primary),
        Triple("onSecondary/secondary", onSecondary, secondary),
        Triple("onTertiary/tertiary", onTertiary, tertiary),
        Triple("onPrimaryContainer/primaryContainer", onPrimaryContainer, primaryContainer),
        Triple("onSecondaryContainer/secondaryContainer", onSecondaryContainer, secondaryContainer),
        Triple("onTertiaryContainer/tertiaryContainer", onTertiaryContainer, tertiaryContainer),
        Triple("onBackground/background", onBackground, background),
        Triple("onSurface/surface", onSurface, surface),
        Triple("onSurfaceVariant/surfaceVariant", onSurfaceVariant, surfaceVariant),
        Triple("onSurface/surfaceContainer", onSurface, surfaceContainer),
        Triple("onSurface/surfaceContainerHighest", onSurface, surfaceContainerHighest),
    )

    private fun catalogue(): Catalog {
        val fromModule = File("src/main/assets", CATALOGUE)
        val file = if (fromModule.isFile) fromModule else File("app/src/main/assets", CATALOGUE)
        return JSON.decodeFromString<Catalog>(file.readText())
    }

    private companion object {
        const val CATALOGUE = "catalog/catalog.json"

        val JSON = Json { ignoreUnknownKeys = true }
    }
}
