package dev.paperouette.wallpaper.data

import dev.paperouette.wallpaper.model.Catalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Layer
import dev.paperouette.wallpaper.model.PaletteColors
import dev.paperouette.wallpaper.model.PreviewAssets
import dev.paperouette.wallpaper.model.Remix
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShufflePickerTest {
    private val current = remix("current", "piece", dark = false)
    private val light = remix("light", "piece", dark = false)
    private val dark = remix("dark", "other", dark = true)
    private val catalogue = snapshot(current, light, dark)

    @Test
    fun everythingExcludesTheCurrentVariantAfterThemePreference() {
        val picked = pick(PaperouetteSettings(), darkMode = false)

        assertEquals(light.id, picked?.id)
    }

    @Test
    fun themePreferenceFallsBackOnlyWhenTheSelectedPoolHasNoMatch() {
        val favouriteDark = PaperouetteSettings(
            shuffleScope = ShuffleScope.FAVORITES,
            favorites = setOf(dark.id),
        )
        assertEquals(dark.id, pick(favouriteDark, darkMode = false)?.id)

        // The order is deliberate: a matching current variant means the pool did have a theme
        // match. Removing it afterwards does not reopen the draw to mismatched variants.
        val currentAndDark = favouriteDark.copy(favorites = setOf(current.id, dark.id))
        assertNull(pick(currentAndDark, darkMode = false))
    }

    @Test
    fun anEmptyOrCurrentOnlyFavouritePoolHasNoAlternative() {
        assertNull(
            pick(PaperouetteSettings(shuffleScope = ShuffleScope.FAVORITES), darkMode = false),
        )
        assertNull(
            pick(
                PaperouetteSettings(
                    shuffleScope = ShuffleScope.FAVORITES,
                    favorites = setOf(current.id),
                ),
                darkMode = false,
            ),
        )
    }

    @Test
    fun aOneVariantCurrentPieceHasNoAlternative() {
        val singlePiece = snapshot(current, dark)

        assertNull(
            ShufflePicker.pick(
                settings = PaperouetteSettings(shuffleScope = ShuffleScope.CURRENT_PIECE),
                current = current,
                catalogue = singlePiece,
                darkMode = false,
                random = Random(1),
            ),
        )
    }

    @Test
    fun aOneVariantCatalogueHasNoAlternativeForEverything() {
        assertNull(
            ShufflePicker.pick(
                settings = PaperouetteSettings(),
                current = current,
                catalogue = snapshot(current),
                darkMode = false,
                random = Random(1),
            ),
        )
    }

    private fun pick(settings: PaperouetteSettings, darkMode: Boolean): Remix? =
        ShufflePicker.pick(settings, current, catalogue, darkMode, Random(1))

    private fun snapshot(vararg remixes: Remix): CatalogSnapshot {
        val designs = remixes.groupBy(Remix::designId).map { (id, own) ->
            Design(id, id, own.first().id, own.map(Remix::id))
        }
        val catalog = Catalog(
            designIds = designs.map(Design::id),
            remixIds = remixes.map(Remix::id),
            designs = designs,
            remixes = remixes.toList(),
        )
        return CatalogSnapshot(catalog.designs, catalog.remixes)
    }

    private fun remix(id: String, designId: String, dark: Boolean) = Remix(
        id = id,
        designId = designId,
        label = id,
        isDark = dark,
        isMultilayered = false,
        type = "parallax",
        previews = PreviewAssets("assets/artwork/test.webp"),
        layers = listOf(Layer("assets/artwork/test.webp", "animated")),
        colors = PaletteColors(loadingColor = 0),
    )
}
