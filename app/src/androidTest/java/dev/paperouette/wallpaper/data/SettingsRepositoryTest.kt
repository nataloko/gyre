package dev.paperouette.wallpaper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Layer
import dev.paperouette.wallpaper.model.PaletteColors
import dev.paperouette.wallpaper.model.PreviewAssets
import dev.paperouette.wallpaper.model.Remix
import dev.paperouette.wallpaper.render.MAX_ANIMATION_SPEED
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    /** Each test writes its own store under the cache; none of them should be left behind. */
    private val stores = mutableListOf<File>()

    private companion object {
        const val HOUR = 3_600_000L

        /** Far enough from zero that a test can put the clock back a day without going negative. */
        const val START_OF_TIME = 30L * 24 * HOUR
    }

    @After
    fun deleteStores() {
        stores.forEach { file ->
            file.delete()
            // DataStore keeps a lock beside the file it is given.
            File(file.parentFile, "${file.name}.lock").delete()
        }
        stores.clear()
    }

    private fun Context.newStoreFile(prefix: String): File =
        File(cacheDir, "$prefix-${System.nanoTime()}.preferences_pb").also(stores::add)

    @Test
    fun defaultsAndRememberedVariantsSurviveSelectionChanges() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("settings")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        assertTrue(repository.settings.value.automaticDarkVariants)
        assertTrue(repository.settings.value.tiltEnabled)
        assertTrue(repository.settings.value.flickEnabled)
        assertEquals(2f, repository.settings.value.touchInertiaSeconds)
        assertFalse(repository.settings.value.tapToSpin)

        repository.selectRemix("maelstrom_hue_moss_dark", darkMode = true)
        assertEquals(
            "maelstrom_hue_moss_dark",
            repository.selection.first { it.remixId == "maelstrom_hue_moss_dark" }.remixId,
        )
        repository.selectRemix("maelstrom_hue_moss", darkMode = false)
        repository.selectForDarkMode(true)
        assertEquals(
            "maelstrom_hue_moss_dark",
            repository.selection.first { it.remixId == "maelstrom_hue_moss_dark" }.remixId,
        )
    }

    @Test
    fun tappingADesignKeepsTheArtworkItsTileShows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("designs")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // In dark mode a design whose only dark remix is an unrelated effect keeps its own
        // preview rather than handing back that effect.
        repository.selectDesign("halo", darkMode = true)
        assertEquals(
            "halo_fx_fade",
            repository.selection.first { it.designId == "halo" }.remixId,
        )

        // Browsing from there reaches every remix of the design, including the dark one.
        repository.nextRemix(darkMode = true)
        assertEquals(
            "halo_fx_grain",
            repository.selection.first { it.remixId == "halo_fx_grain" }.remixId,
        )

        // A design that genuinely pairs light and dark still swaps to the twin.
        repository.selectDesign("maelstrom", darkMode = true)
        assertEquals(
            "maelstrom_hue_moss_dark",
            repository.selection.first { it.designId == "maelstrom" }.remixId,
        )
    }

    @Test
    fun anExplicitVariantOutlivesTheWallpaperComingBackIntoView() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("sticky")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // The reported bug: on a light-themed phone every tap in the variant strip went back to
        // the piece's own artwork, because the wallpaper re-resolved the theme each time it
        // became visible and "Haze" does not measure light.
        repository.selectRemix("nightfall_fx_haze", darkMode = false)
        repository.selection.first { it.remixId == "nightfall_fx_haze" }
        repeat(3) { repository.selectForDarkMode(false) }

        assertEquals("nightfall_fx_haze", repository.selection.value.remixId)
    }

    @Test
    fun aVariantWithNoTwinIsKeptWhenTheThemeFlips() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("notwin")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // 194 of the 340 variants have no twin. There is nothing to move to, so the theme change
        // leaves the choice alone rather than reaching for the piece's preview.
        repository.selectRemix("nightfall_fx_haze", darkMode = false)
        repository.selection.first { it.remixId == "nightfall_fx_haze" }
        repository.selectForDarkMode(true)

        assertEquals("nightfall_fx_haze", repository.selection.value.remixId)
    }

    @Test
    fun aThemeFlipTakesTheTwinOfWhatIsShowing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("showing")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // From "Slate" the dark form is "Slate (Dark)", not the preview's "Moss (Dark)".
        repository.selectRemix("maelstrom_hue_slate", darkMode = false)
        repository.selection.first { it.remixId == "maelstrom_hue_slate" }
        repository.selectForDarkMode(true)

        assertEquals(
            "maelstrom_hue_slate_dark",
            repository.selection.first { it.remixId == "maelstrom_hue_slate_dark" }.remixId,
        )
    }

    @Test
    fun aLabelThatMerelyEndsInDarkIsNotHalfOfAPair() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("darkish")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // Spinner 32 ships a colour called "Dark". Stripping that as a suffix would make it the
        // twin of a variant with no label at all.
        repository.selectRemix("spindle_color_0", darkMode = true)
        repository.selection.first { it.remixId == "spindle_color_0" }
        repository.selectForDarkMode(false)

        assertEquals("spindle_color_0", repository.selection.value.remixId)
    }

    @Test
    fun aVariantPickedByAnEarlierBuildIsNotResolvedAwayOnUpgrade() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("upgrade")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        // The state an upgrading phone arrives with: a selection, and no record of the theme it
        // was resolved against. A correct fix still looks broken if that is swapped out on sight.
        store.edit { preferences ->
            preferences[stringPreferencesKey("active_design")] = "nightfall"
            preferences[stringPreferencesKey("active_remix")] = "nightfall_fx_haze"
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        repository.selectForDarkMode(false)

        assertEquals(
            "nightfall_fx_haze",
            repository.selection.first { it.designId == "nightfall" }.remixId,
        )
    }

    @Test
    fun switchingOnMatchingTheThemeKeepsThePieceItIsShowing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("switchon")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        repository.update { it.copy(automaticDarkVariants = false) }
        repository.selectRemix("maelstrom_hue_slate", darkMode = false)
        repository.selection.first { it.remixId == "maelstrom_hue_slate" }

        // Switching the setting on moves to this variant's own dark form, not to the piece's.
        repository.update { it.copy(automaticDarkVariants = true) }
        repository.applyDarkMode(true)

        assertEquals(
            "maelstrom_hue_slate_dark",
            repository.selection.first { it.remixId == "maelstrom_hue_slate_dark" }.remixId,
        )
    }

    @Test
    fun theThemeIsNotFollowedWhileTheSettingIsOff() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("manual")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        repository.update { it.copy(automaticDarkVariants = false) }
        repository.selectRemix("maelstrom_hue_slate", darkMode = false)
        repository.selection.first { it.remixId == "maelstrom_hue_slate" }
        repository.selectForDarkMode(true)
        runCurrent()

        assertEquals("maelstrom_hue_slate", repository.selection.value.remixId)
    }

    @Test
    fun aPairWhoseHalvesBothMeasureDarkStillSwaps() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("bothdark")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // "Gold" and "Gold (Dark)" both measure dark, as every Pocket Planetarium pair does.
        // Pairing by tone found neither, so the design's dark twin was unreachable.
        repository.selectDesign("nightfall", darkMode = true)
        assertEquals(
            "nightfall_hue_gold_dark",
            repository.selection.first { it.designId == "nightfall" }.remixId,
        )

        // The light side is the preview itself; there is nothing lighter to swap to.
        repository.selectDesign("nightfall", darkMode = false)
        assertEquals(
            "nightfall_hue_gold",
            repository.selection.first { it.remixId == "nightfall_hue_gold" }.remixId,
        )
    }

    @Test
    fun aTwinIsFoundByItsLabelWhenTheIdsDoNotPair() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("labelpair")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // Spinner 32's pairs are `_color_1` and `_color_2`; only the labels relate them.
        repository.selectDesign("spindle", darkMode = true)
        assertEquals(
            "spindle_color_2",
            repository.selection.first { it.designId == "spindle" }.remixId,
        )
    }

    @Test
    fun variantsRememberedByEarlierBuildsAreNotTrusted() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("legacy")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        // Builds before 1.0.3 recorded their own automatic pick as though the user had chosen it,
        // so upgrading kept handing back the unrelated dark effect this map names.
        store.edit { preferences ->
            preferences[stringPreferencesKey("remembered_variants")] =
                """{"light":{},"dark":{"halo":"halo_fx_grain"}}"""
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        repository.selectDesign("halo", darkMode = true)

        assertEquals(
            "halo_fx_fade",
            repository.selection.first { it.designId == "halo" }.remixId,
        )
    }

    @Test
    fun aPickIsFiledUnderTheThemeItWasMadeIn() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("filing")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // "Haze" measures dark but is picked on a light-themed phone, as every Pocket Planetarium
        // variant is. Filed by tone it went into the dark drawer and the light one stayed empty,
        // so coming back to the design in light handed over the preview instead.
        repository.selectRemix("nightfall_fx_haze", darkMode = false)
        repository.selectDesign("maelstrom", darkMode = false)
        repository.selection.first { it.designId == "maelstrom" }
        repository.selectDesign("nightfall", darkMode = false)

        assertEquals(
            "nightfall_fx_haze",
            repository.selection.first { it.designId == "nightfall" }.remixId,
        )
    }

    @Test
    fun variantsRememberedByVersionFourAreNotTrusted() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("version4")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        // Version four filed a pick by the tone of its artwork. Read as though it were filed by
        // the theme it was picked under, its entries claim choices that were never made.
        store.edit { preferences ->
            preferences[intPreferencesKey("remembered_variants_version")] = 4
            preferences[stringPreferencesKey("remembered_variants")] =
                """{"light":{},"dark":{"nightfall":"nightfall_fx_haze"}}"""
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        repository.selectDesign("nightfall", darkMode = true)

        assertEquals(
            "nightfall_hue_gold_dark",
            repository.selection.first { it.designId == "nightfall" }.remixId,
        )
    }

    @Test
    fun choosingADesignDoesNotOverwriteAnExplicitRemixChoice() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("explicit")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )

        // Picking a remix by hand is a real choice and comes back on return to the design.
        repository.selectRemix("halo_fx_grain", darkMode = true)
        repository.selectDesign("maelstrom", darkMode = true)
        repository.selection.first { it.designId == "maelstrom" }
        repository.selectDesign("halo", darkMode = true)

        assertEquals(
            "halo_fx_grain",
            repository.selection.first { it.designId == "halo" }.remixId,
        )
    }

    @Test
    fun theRotationCentreIsKeptAndBounded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("pivot")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { preferences ->
            preferences[floatPreferencesKey("rotation_center_x")] = 9f
            preferences[floatPreferencesKey("rotation_center_y")] = Float.NaN
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )
        runCurrent()

        // Out of range clamps, unusable falls back to the middle.
        assertEquals(1f, repository.settings.value.rotationCenterX)
        assertEquals(0.5f, repository.settings.value.rotationCenterY)

        repository.update { it.copy(rotationCenterX = 0.3f, rotationCenterY = 0.8f) }
        assertEquals(
            0.3f,
            repository.settings.first { it.rotationCenterX == 0.3f }.rotationCenterX,
        )
        assertEquals(0.8f, repository.settings.value.rotationCenterY)
    }

    @Test
    fun theSpeedIsKeptAndBounded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("speed")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { preferences ->
            preferences[floatPreferencesKey("animation_speed")] = 99f
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )
        runCurrent()

        assertEquals(MAX_ANIMATION_SPEED, repository.settings.value.animationSpeed)

        // Still is a real setting rather than a value to be rejected, so it has to survive a round
        // trip like any other — falling back to 1 here would start the artwork moving again.
        repository.update { it.copy(animationSpeed = 0f) }
        assertEquals(0f, repository.settings.first { it.animationSpeed == 0f }.animationSpeed)
    }

    @Test
    fun givingWayToTheAppDrawerIsOnUntilItIsTurnedOff() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("zoom")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )
        runCurrent()

        assertTrue(repository.settings.value.launcherZoomEnabled)

        repository.update { it.copy(launcherZoomEnabled = false) }
        assertFalse(repository.settings.first { !it.launcherZoomEnabled }.launcherZoomEnabled)
    }

    @Test
    fun theChangeIntervalIsKeptAndBounded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("interval")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { preferences ->
            preferences[intPreferencesKey("random_change_hours")] = 999
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )
        runCurrent()

        assertEquals(MAX_RANDOM_CHANGE_HOURS, repository.settings.value.randomChangeHours)

        // Off is a real setting rather than a value to be rejected: it is the whole way of saying
        // "leave my wallpaper alone", so it has to survive a round trip like any other.
        repository.update { it.copy(randomChangeHours = 0) }
        assertEquals(0, repository.settings.first { it.randomChangeHours == 0 }.randomChangeHours)
    }

    @Test
    fun nothingChangesOnItsOwnWhileTheIntervalIsOff() = runTest {
        var now = START_OF_TIME
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("never")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
            clock = { now },
        )
        runCurrent()

        repository.selectRemix("maelstrom_hue_moss", darkMode = false)
        val chosen = repository.selection.first { it.remixId == "maelstrom_hue_moss" }.remixId

        now += 7L * 24 * HOUR
        assertFalse(repository.shuffleIfDue(darkMode = false))
        assertEquals(chosen, repository.selection.value.remixId)
    }

    /**
     * The interval is spent, and only then. Six hours means nothing at five and something at seven
     * — the whole feature, and the only part of it a user can check without waiting a day.
     */
    @Test
    fun theVariantChangesOnceTheIntervalHasPassed() = runTest {
        var now = START_OF_TIME
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("due")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
            clock = { now },
            random = Random(7),
        )
        runCurrent()

        repository.selectRemix("maelstrom_hue_moss", darkMode = false)
        repository.selection.first { it.remixId == "maelstrom_hue_moss" }
        repository.update { it.copy(randomChangeHours = 6) }
        repository.settings.first { it.randomChangeHours == 6 }

        now += 5 * HOUR
        assertFalse(repository.shuffleIfDue(darkMode = false))
        assertEquals("maelstrom_hue_moss", repository.selection.value.remixId)

        now += 2 * HOUR
        assertTrue(repository.shuffleIfDue(darkMode = false))
        val moved = repository.selection.first { it.remixId != "maelstrom_hue_moss" }.remixId

        // And the interval starts again from the change it just made, rather than from the last
        // time the clock was looked at.
        now += 5 * HOUR
        assertFalse(repository.shuffleIfDue(darkMode = false))
        assertEquals(moved, repository.selection.value.remixId)
    }

    /**
     * Turning it on starts the interval, rather than spending it.
     *
     * The stamp is of the last change by any means, so without this a phone that had been on one
     * variant for a week would swap the moment "every 24 hours" was set — reading as the setting
     * ignoring its own number.
     */
    @Test
    fun settingTheIntervalRunsItFromNowRatherThanFromTheLastChange() = runTest {
        var now = START_OF_TIME
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("fromnow")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
            clock = { now },
            random = Random(3),
        )
        runCurrent()

        repository.selectRemix("maelstrom_hue_moss", darkMode = false)
        repository.selection.first { it.remixId == "maelstrom_hue_moss" }

        now += 7L * 24 * HOUR
        repository.update { it.copy(randomChangeHours = 6) }
        repository.settings.first { it.randomChangeHours == 6 }

        assertFalse(repository.shuffleIfDue(darkMode = false))
        assertEquals("maelstrom_hue_moss", repository.selection.value.remixId)

        now += 7 * HOUR
        assertTrue(repository.shuffleIfDue(darkMode = false))
    }

    /**
     * A change the app made is not a choice the user made.
     *
     * Recording the app's own selection in the remembered map is what let a wrong resolution
     * outlive the fix that corrected it, and a draw from the whole collection is even less of a
     * choice than that was. So the map is compared byte for byte across a timed change: whatever
     * the draw lands on, the memory has to be exactly what it was.
     */
    @Test
    fun aChangeMadeOnItsOwnIsNotRememberedAsAPick() = runTest {
        var now = START_OF_TIME
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("unremembered")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
            clock = { now },
            random = Random(11),
        )
        runCurrent()

        repository.selectRemix("maelstrom_hue_slate", darkMode = false)
        repository.selection.first { it.remixId == "maelstrom_hue_slate" }
        repository.update { it.copy(randomChangeHours = 1) }
        repository.settings.first { it.randomChangeHours == 1 }
        val remembered = store.data.first()[stringPreferencesKey("remembered_variants")]

        now += 2 * HOUR
        assertTrue(repository.shuffleIfDue(darkMode = false))

        assertEquals(remembered, store.data.first()[stringPreferencesKey("remembered_variants")])
        // And the hand-picked variant is still what tapping the piece brings back.
        repository.selectDesign("maelstrom", darkMode = false)
        assertEquals(
            "maelstrom_hue_slate",
            repository.selection.first { it.remixId == "maelstrom_hue_slate" }.remixId,
        )
    }

    /** A clock put back starts the interval again rather than making every check due. */
    @Test
    fun aClockPutBackDoesNotKeepChangingTheVariant() = runTest {
        var now = START_OF_TIME
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("backwards")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
            clock = { now },
            random = Random(5),
        )
        runCurrent()

        repository.update { it.copy(randomChangeHours = 6) }
        repository.settings.first { it.randomChangeHours == 6 }

        now -= 24 * HOUR
        assertFalse(repository.shuffleIfDue(darkMode = false))
        val settled = repository.selection.value.remixId

        now += HOUR
        assertFalse(repository.shuffleIfDue(darkMode = false))
        assertEquals(settled, repository.selection.value.remixId)

        // The interval is measured from the corrected clock, so it still comes due.
        now += 6 * HOUR
        assertTrue(repository.shuffleIfDue(darkMode = false))
    }

    @Test
    fun invalidStoredIdsAndValuesAreDiscarded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.newStoreFile("invalid")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { preferences ->
            preferences[stringPreferencesKey("active_remix")] = "missing"
            preferences[stringSetPreferencesKey("favorites")] = setOf("missing")
            preferences[floatPreferencesKey("dim")] = Float.NaN
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = store,
        )
        runCurrent()

        // The stored id names nothing, so the selection falls back to something that resolves.
        // Not to ActiveSelection.DEFAULT_REMIX, which this fake catalogue does not contain —
        // asserting that only ever passed while the first emission had yet to land.
        assertEquals(FakeCatalogue.first.id, repository.selection.value.remixId)
        assertEquals(FakeCatalogue.first.designId, repository.selection.value.designId)
        assertTrue(repository.settings.value.favorites.isEmpty())
        assertEquals(0f, repository.settings.value.dim)
    }

    @Test
    fun storageIoFailureFallsBackToDefaults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unavailable") }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("unavailable")
        }
        val repository = DataStoreSettingsRepository(
            context = context,
            catalogues = FakeCatalogue,
            scope = backgroundScope,
            dataStoreOverride = failingStore,
        )
        runCurrent()

        assertEquals(PaperouetteSettings(), repository.settings.value)
        assertEquals(
            ActiveSelection(FakeCatalogue.first.designId, FakeCatalogue.first.id),
            repository.selection.value,
        )
    }

    private object FakeCatalogue : CatalogRepository {
        /** What an unresolvable selection falls back to, this catalogue having no planetarium. */
        val first: Remix get() = current.value.remixes.first()

        // A design that pairs each colour with a dark twin, as the colour designs do. Two pairs,
        // so "the twin of what is showing" can be told apart from "the twin of the preview".
        private val light = remix("maelstrom_hue_moss", "maelstrom", "Moss", dark = false)
        private val dark = remix("maelstrom_hue_moss_dark", "maelstrom", "Moss (Dark)", dark = true)
        private val slate = remix("maelstrom_hue_slate", "maelstrom", "Slate", dark = false)
        private val slateDark =
            remix("maelstrom_hue_slate_dark", "maelstrom", "Slate (Dark)", dark = true)

        // A design whose dark remix is an unrelated effect, as every effect design is.
        private val stroked = remix("halo_fx_fade", "halo", "Fade", dark = false)
        private val dotBomb = remix("halo_fx_grain", "halo", "Grain", dark = true)

        // A design whose every remix measures dark, as all twelve of Pocket Planetarium's do —
        // the shape the older fixtures could not express, and where the bug lived.
        private val gold = remix("nightfall_hue_gold", "nightfall", "Gold", dark = true)
        private val goldDark =
            remix("nightfall_hue_gold_dark", "nightfall", "Gold (Dark)", dark = true)
        private val haze = remix("nightfall_fx_haze", "nightfall", "Haze", dark = true)

        // A design that pairs by label with no id relationship, as Spinner 32 does, and whose
        // "Dark" is a colour of its own rather than half of a pair.
        private val blue = remix("spindle_color_1", "spindle", "Blue", dark = false)
        private val blueDark = remix("spindle_color_2", "spindle", "Blue (Dark)", dark = true)
        private val darkest = remix("spindle_color_0", "spindle", "Dark", dark = true)

        private val designs = listOf(
            Design(
                id = "maelstrom",
                label = "Maelstrom",
                previewRemixId = light.id,
                remixIds = listOf(light.id, dark.id, slate.id, slateDark.id),
            ),
            Design(
                id = "halo",
                label = "Halo",
                previewRemixId = stroked.id,
                remixIds = listOf(dotBomb.id, stroked.id),
            ),
            Design(
                id = "nightfall",
                label = "Nightfall",
                previewRemixId = gold.id,
                remixIds = listOf(gold.id, goldDark.id, haze.id),
            ),
            Design(
                id = "spindle",
                label = "Spindle",
                previewRemixId = blue.id,
                remixIds = listOf(blue.id, blueDark.id, darkest.id),
            ),
        )
        private val remixes = listOf(
            light,
            dark,
            dotBomb,
            stroked,
            slate,
            slateDark,
            gold,
            goldDark,
            haze,
            blue,
            blueDark,
            darkest,
        )

        override val current = MutableStateFlow(CatalogSnapshot(designs, remixes))

        private fun remix(id: String, designId: String, label: String, dark: Boolean) = Remix(
            id = id,
            designId = designId,
            label = label,
            isDark = dark,
            isMultilayered = false,
            type = "parallax",
            previews = PreviewAssets(thumb = "assets/artwork/test.webp"),
            layers = listOf(Layer("assets/artwork/test.webp", "animated")),
            colors = PaletteColors(loadingColor = 0),
        )
    }
}
