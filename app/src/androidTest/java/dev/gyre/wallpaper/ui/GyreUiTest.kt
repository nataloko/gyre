package dev.gyre.wallpaper.ui

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gyre.wallpaper.BuildConfig
import dev.gyre.wallpaper.GyreApplication
import dev.gyre.wallpaper.MainActivity
import dev.gyre.wallpaper.data.ActiveSelection
import dev.gyre.wallpaper.data.GyreSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The interface is one screen: the artwork at full size, a sheet over it, and two panels over that.
 * These drive it the way a hand would — there are no destinations to navigate between.
 */
@RunWith(AndroidJUnit4::class)
class GyreUiTest {
    /**
     * Puts the settings back to their defaults, before the activity is launched.
     *
     * These run against the real installed app, so its DataStore outlives the process and every
     * method used to inherit whatever the last one left. That is not merely untidy: a test that
     * asserts a fader reads 60% passes on the second run whether or not it moved the fader, because
     * the previous run left it there. Ordered ahead of the compose rule so the write lands before
     * anything reads it, which is why this is a chain rather than a `@Before`.
     */
    private val defaultSettings = object : ExternalResource() {
        override fun before() {
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as GyreApplication
            runBlocking {
                application.settings.update { GyreSettings() }
                // Under the device's own theme, so the selection is stamped as already resolved
                // for it and the activity's follow-the-theme effect is a deterministic no-op.
                application.settings.selectRemix(ActiveSelection.DEFAULT_REMIX, deviceIsDark())
            }
        }
    }

    private fun deviceIsDark(): Boolean {
        val uiMode = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.uiMode
        return uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(defaultSettings).around(compose)

    @Test
    fun theStageIsTheGroundAndTheCollectionIsUnderTheSheet() {
        compose.onNodeWithTag("live_stage").assertIsDisplayed()
        compose.onNodeWithTag("set_wallpaper").assertIsDisplayed()
        compose.onNodeWithTag("variant_strip").assertIsDisplayed()

        expandSheet()

        compose.onNodeWithTag("collection_body").assertIsDisplayed()
        // A piece well down the grid, so reaching it proves the collection scrolls rather than
        // merely that its first row drew. "Filament" used to stand here and left with the
        // catalogue it belonged to, which the test did not notice for six commits.
        compose.onNodeWithTag("collection_body")
            .performScrollToNode(hasTextExactly("Rainbow Whirlpool"))
        compose.onNodeWithText("Rainbow Whirlpool").assertIsDisplayed()
    }

    /**
     * Importing is a chip beside the filter, not a screen of its own.
     *
     * It only checks that the way in is there and reachable: tapping it hands over to the system
     * file picker, which is another app's window and nothing this test can drive.
     */
    @Test
    fun theCollectionOffersAWayToImportArtwork() {
        expandSheet()

        compose.onNodeWithTag("collection_body")
            .performScrollToNode(hasTestTag("import_artwork"))
        compose.onNodeWithTag("import_artwork").assertIsDisplayed().assertHasClickAction()
        // Nothing is importing, so the status line is absent rather than merely empty.
        compose.onAllNodesWithTag("import_status").assertCountEquals(0)

        // The two ways in appear only once asked for, so the header stays one row wide.
        compose.onAllNodesWithTag("import_file").assertCountEquals(0)
        compose.onNodeWithTag("import_artwork").performClick()
        compose.onNodeWithTag("import_file").assertIsDisplayed()
        compose.onNodeWithTag("import_folder").assertIsDisplayed()
    }

    @Test
    fun favouritesFilterKeepsWhatTheHeartAdded() {
        val toAdd = compose.onAllNodesWithContentDescription("Add favourite")
        if (toAdd.fetchSemanticsNodes().isNotEmpty()) toAdd[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Remove favourite")
                .fetchSemanticsNodes().isNotEmpty()
        }

        expandSheet()
        compose.onNodeWithTag("favourites_filter").performClick()

        // The kept variant's own tile carries a heart of its own, on top of the one in the peek row.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Remove favourite")
                .fetchSemanticsNodes().size >= 2
        }
    }

    @Test
    fun aFaderCommitsAndSurvivesClosingThePanel() {
        compose.onNodeWithTag("open_look").performClick()
        compose.onNodeWithTag("fader_dim").assertIsDisplayed()
        // Nothing reads 60% yet — the settings were reset — so reaching it below can only be this
        // test's doing rather than a leftover from the last run.
        compose.onAllNodes(hasTextExactly("60%")).assertCountEquals(0)

        compose.onNodeWithTag("fader_dim")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTextExactly("60%")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("close_look").performClick()
        compose.onNodeWithTag("open_look").performClick()

        compose.onNodeWithText("60%").assertIsDisplayed()
    }

    /** Held still, the scene stops running its own animation but the stage keeps answering. */
    @Test
    fun speedCanBeTakenAllTheWayDownToStill() {
        compose.onNodeWithTag("open_look").performClick()
        compose.onNodeWithTag("fader_speed").assertIsDisplayed()

        compose.onNodeWithTag("fader_speed")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTextExactly("Still")).fetchSemanticsNodes().isNotEmpty()
        }
        // The stage says it is animating regardless: holding the scene still is not pausing it, and
        // a touch still spins it.
        compose.onNodeWithTag("close_look").performClick()
        compose.onNodeWithTag("live_stage").assertIsDisplayed()
    }

    /**
     * The timed change is off out of the box, and says so in words rather than as a bare 0.
     *
     * Asserted from the defaults the reset rule installs rather than from whatever the last method
     * left, since a fader reading the value it was already at proves nothing about the control.
     */
    @Test
    fun theWallpaperChangesOnItsOwnOnlyOnceAskedTo() {
        compose.onNodeWithTag("open_behaviour").performClick()
        compose.onNodeWithTag("behaviour_panel").performScrollToKey("random_change")

        compose.onNodeWithTag("fader_random_change").assertIsDisplayed()
        compose.onNodeWithText("Never").assertIsDisplayed()

        // Halfway along the track is twelve of the twenty-four hours it offers.
        compose.onNodeWithTag("fader_random_change")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        compose.onNodeWithText("12 hours").assertIsDisplayed()
    }

    /** Installed over adb rather than from a store, so the build has to say which one it is. */
    @Test
    fun theBuildNamesItselfAtTheFootOfBehaviour() {
        compose.onNodeWithTag("open_behaviour").performClick()
        compose.onNodeWithTag("behaviour_panel").performScrollToKey("version")

        compose.onNodeWithTag("version")
            .assertIsDisplayed()
            .assertTextEquals("Gyre ${BuildConfig.VERSION_NAME}")
    }

    @Test
    fun anOpenPanelAndItsScrollPositionSurviveRecreation() {
        compose.onNodeWithTag("open_behaviour").performClick()
        // Scrolled by the item's key rather than by its text: how far down the panel a row sits
        // depends on how tall the window is, and the suite must not care which way the phone is
        // being held.
        compose.onNodeWithTag("behaviour_panel").performScrollToKey("battery")
        compose.onNodeWithText(BATTERY_ROW).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("behaviour_panel").assertIsDisplayed()
        compose.onNodeWithText(BATTERY_ROW).assertIsDisplayed()
    }

    @Test
    fun anExplicitlyChosenDarkVariantIsTheOneNamedOnTheSheet() {
        expandSheet()
        compose.onNodeWithTag("collection_body").performScrollToNode(hasTextExactly("Pocket Planetarium"))
        // The piece's name is on the sheet's top row as well as on its tile; only the tile is a
        // target.
        compose.onNode(hasTextExactly("Pocket Planetarium") and hasClickAction()).performClick()

        // Strip tiles are named rather than labelled: within a piece the names are long and alike,
        // so the tile shows the artwork and tells a screen reader the name. The strip is lazy, so
        // how many are composed depends on how wide it is — scroll to the one wanted by its key.
        compose.onNodeWithTag("variant_strip").performScrollToKey(GOLDEN_DARK)
        compose.onNodeWithContentDescription("Golden Hour (Dark)").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTextExactly("Golden Hour (Dark)")).fetchSemanticsNodes().isNotEmpty()
        }
        // The sheet's own row, told apart from the tile of the same name by not being a target.
        compose.onNode(hasTextExactly("Pocket Planetarium") and hasNoClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Golden Hour (Dark)").assertIsDisplayed()
    }

    @Test
    fun openingLookDropsTheCollectionOutOfTheWay() {
        expandSheet()
        compose.onNodeWithTag("collection_body").assertIsDisplayed()

        compose.onNodeWithTag("open_look").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("fader_dim")).fetchSemanticsNodes().isNotEmpty()
        }

        // Only the phone layout has a sheet to drop. On a wide window the collection is docked
        // beside the stage rather than over it, so it is not in the way to begin with.
        val docked = compose.onAllNodes(hasTestTag("side_panel")).fetchSemanticsNodes().isNotEmpty()
        if (!docked) compose.onNodeWithTag("collection_body").assertIsNotDisplayed()
    }

    @Test
    fun mirroringIsRememberedAcrossOpeningThePanelAgain() {
        compose.onNodeWithTag("open_look").performClick()
        // Mirror is off by default and the settings were reset, so this starts from a known state
        // rather than from whatever the last test left.
        compose.onNodeWithTag("mirror_toggle").assertIsOff()

        // Scrolled to before it is tapped. On a landscape phone the Filters tab is taller than the
        // stage it sits in — the panel is built to scroll for exactly that — so the toggles can sit
        // below the fold, and a tap aimed at the middle of one would land off the bottom.
        compose.onNodeWithTag("mirror_toggle").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { mirrorIsOn() }

        compose.onNodeWithTag("close_look").performClick()
        // Wait for the panel to actually be gone. Reopening while it is still animating out puts
        // the tap on the panel instead of on the button behind it.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("mirror_toggle")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("open_look").performClick()

        // The point of the test: it came back on, rather than back to its default.
        compose.waitUntil(timeoutMillis = 5_000) { mirrorIsOn() }
        compose.onNodeWithTag("mirror_toggle").assertIsOn()
    }

    private fun mirrorIsOn(): Boolean =
        compose.onAllNodes(hasTestTag("mirror_toggle") and isOn()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theCentreArrowsMoveThePointOneStepEach() {
        compose.onNodeWithTag("open_look").performClick()
        compose.onNodeWithTag("look_tab_centre").performClick()

        // Start from a known point. The reset only exists once the centre has been moved, so its
        // absence already means the middle.
        val reset = compose.onAllNodes(hasTestTag("reset_rotation_center"))
        if (reset.fetchSemanticsNodes().isNotEmpty()) reset[0].performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTextExactly(MIDDLE_READOUT)).fetchSemanticsNodes().isNotEmpty()
        }

        repeat(3) { compose.onNodeWithContentDescription("Move the centre right").performClick() }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTextExactly("53% across · 50% down"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The same numbers ride along with the crosshair, where the eye actually is.
        compose.onNodeWithTag("centre_readout").assertTextEquals("53% · 50%")
    }

    @Test
    fun playTakesTheControlsAwayAndBackReturnsThem() {
        compose.onNodeWithTag("enter_play").assertIsDisplayed()

        compose.onNodeWithTag("enter_play").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("enter_play")).fetchSemanticsNodes().isEmpty()
        }
        // The sheet slides off the bottom on a phone and is dropped entirely on a wide window, so
        // the control may be either gone or merely out of sight — never on screen.
        if (compose.onAllNodes(hasTestTag("set_wallpaper")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("set_wallpaper").assertIsNotDisplayed()
        }

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("enter_play")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("set_wallpaper").assertIsDisplayed()
    }

    @Test
    fun batterySaverPausesAndResumesTheStage() {
        val powerManager = compose.activity.getSystemService(PowerManager::class.java)
        val originalMode = powerManager.isPowerSaveMode
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        try {
            executeShellCommand("dumpsys battery unplug")
            setBatterySaver(false)
            compose.waitUntil(timeoutMillis = 5_000) { !powerManager.isPowerSaveMode }
            waitForStageState("Animating")

            setBatterySaver(true)
            compose.waitUntil(timeoutMillis = 5_000) { powerManager.isPowerSaveMode }
            waitForStageState("Paused for battery saver")

            setBatterySaver(false)
            compose.waitUntil(timeoutMillis = 5_000) { !powerManager.isPowerSaveMode }
            waitForStageState("Animating")
        } finally {
            setBatterySaver(originalMode)
            executeShellCommand("dumpsys battery reset")
            compose.activityRule.scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Pulls the sheet all the way up by dragging its peek content, as a thumb would. */
    private fun expandSheet() {
        compose.onNodeWithTag("sheet_peek").performTouchInput { swipeUp() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("favourites_filter")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun setBatterySaver(enabled: Boolean) {
        val value = if (enabled) 1 else 0
        executeShellCommand("cmd power set-mode $value")
    }

    private fun executeShellCommand(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun stageState(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun waitForStageState(value: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(
                hasTestTag("live_stage") and stageState(value),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hasTextExactly(text: String): SemanticsMatcher =
        androidx.compose.ui.test.hasTextExactly(text)

    private companion object {
        /**
         * Scrolled and asserted against instead of the "Power" heading above it: section headings
         * are drawn in capitals, so their text is "POWER" and matching "Power" never finds them.
         */
        const val BATTERY_ROW = "Hold still on battery saver"

        /** Every hue variant has a dark twin named for it, which is what this steps between. */
        const val GOLDEN_DARK = "planetarium_hue_golden_dark"

        /** Note the middle dot: the readout uses U+00B7, not a full stop. */
        const val MIDDLE_READOUT = "50% across · 50% down"
    }
}
