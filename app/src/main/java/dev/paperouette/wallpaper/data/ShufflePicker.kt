package dev.paperouette.wallpaper.data

import dev.paperouette.wallpaper.model.Remix
import kotlin.random.Random

/** The common candidate rules for a button shuffle and a timed change. */
internal object ShufflePicker {
    fun pick(
        settings: PaperouetteSettings,
        current: Remix,
        catalogue: CatalogSnapshot,
        darkMode: Boolean,
        random: Random,
    ): Remix? {
        val pool = when (settings.shuffleScope) {
            ShuffleScope.EVERYTHING -> catalogue.remixes
            ShuffleScope.FAVORITES -> catalogue.remixes.filter { it.id in settings.favorites }
            ShuffleScope.CURRENT_PIECE -> catalogue.remixesFor(current.designId)
        }
        val preferred = if (settings.automaticDarkVariants) {
            pool.filter { it.isDark == darkMode }.ifEmpty { pool }
        } else {
            pool
        }
        return preferred.filterNot { it.id == current.id }.randomOrNull(random)
    }
}
