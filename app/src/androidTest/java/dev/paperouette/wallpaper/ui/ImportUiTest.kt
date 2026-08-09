package dev.paperouette.wallpaper.ui

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.paperouette.wallpaper.PaperouetteApplication
import dev.paperouette.wallpaper.MainActivity
import dev.paperouette.wallpaper.data.ImportProgress
import dev.paperouette.wallpaper.data.TestPacks
import dev.paperouette.wallpaper.data.ZipImportSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * What an import looks like once it has landed.
 *
 * The system file picker is another app's window and cannot be driven from here, so the pack is
 * imported through [dev.paperouette.wallpaper.data.ImportSource] — the seam the picker stops at — and the
 * test is about what the collection then shows.
 */
@RunWith(AndroidJUnit4::class)
class ImportUiTest {
    private val application: PaperouetteApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PaperouetteApplication

    /** Seeded before the activity launches, so the collection is already showing it. */
    private val seedImport = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            runBlocking {
                clearImports()
                application.importer.start(
                    ZipImportSource("Holiday.zip") { TestPacks.build(context).inputStream() }
                )
                val end = application.importer.progress.first {
                    it is ImportProgress.Finished || it is ImportProgress.Failed
                }
                check(end is ImportProgress.Finished) { "seed import failed: $end" }
                application.importer.acknowledge()
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedImport).around(compose)

    @After
    fun removeImports() = runBlocking { clearImports() }

    private suspend fun clearImports() {
        application.importer.imported.value.forEach { application.importer.remove(it.manifest.id) }
    }

    /**
     * Imported pieces are grouped under the import that brought them, and named by it.
     *
     * They have to be: a pack made from the artwork this catalogue was recreated from carries the
     * very same labels, so a flat grid would show two pieces of the same name and nothing to tell
     * them apart.
     */
    @Test
    fun anImportIsNamedAboveThePiecesItBrought() {
        expandSheet()

        compose.onNodeWithTag("collection_body").performScrollToNode(hasTestTag("import_header"))
        compose.onNodeWithTag("import_header").assertIsDisplayed()
        // The pack's own name, not the file's: a pack says what it is called, and the filename
        // it happened to arrive under is only the fallback for one that does not.
        compose.onNode(hasText("Fixture", substring = true)).assertIsDisplayed()
        // Counts read as prose rather than as "1 pieces", and a small import is not "0 MB".
        compose.onNode(hasText("1 piece · 2 variants", substring = true)).assertIsDisplayed()
    }

    /**
     * A bar and a heading close the bundled run off from the imported one.
     *
     * Without them the two meet at nothing but the grid's own row spacing, and a pack's name reads
     * as one more piece's label rather than as the start of a section. The heading is the boundary
     * rather than a description of what sits under it, so it does not outlive one: take the import
     * away and the collection is one thing again, with nothing to tell apart.
     */
    @Test
    fun importedPiecesAreClosedOffFromTheBundledRun() {
        expandSheet()

        compose.onNodeWithTag("collection_body")
            .performScrollToNode(hasTestTag("collection_imported"))
        compose.onNodeWithTag("collection_imported").assertIsDisplayed()

        runBlocking { clearImports() }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("collection_imported").fetchSemanticsNodes().isEmpty()
        }
    }

    /** Removal is attached to the thing being removed, and asks once before doing it. */
    @Test
    fun removingAnImportTakesItsPiecesOutOfTheCollection() {
        expandSheet()
        compose.onNodeWithTag("collection_body").performScrollToNode(hasTestTag("import_header"))

        compose.onNodeWithTag("remove_import").assertHasClickAction().performClick()
        // The first tap only arms it; the pieces are still there.
        compose.waitForIdle()
        assertEquals(1, application.importer.imported.value.size)

        compose.onNodeWithTag("remove_import").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            application.importer.imported.value.isEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("import_header").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun expandSheet() {
        compose.onNodeWithTag("sheet_peek").performTouchInput { swipeUp() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("favourites_filter").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
}
