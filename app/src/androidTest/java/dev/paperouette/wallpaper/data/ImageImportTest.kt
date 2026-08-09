package dev.paperouette.wallpaper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperouette.wallpaper.render.OffscreenRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Importing ordinary pictures: what a photograph becomes, and that the renderer will draw it.
 *
 * The pictures are drawn here rather than shipped, so the fixtures state their own shape — a wide
 * one to be cropped, a tall one, a tiny one that must not be enlarged, and something that is not an
 * image at all.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ImageImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var filesDir: File
    private lateinit var store: ImportStore

    @Before
    fun setUp() {
        filesDir = File(context.cacheDir, "images-test-${System.nanoTime()}").apply { mkdirs() }
        store = ImportStore(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun aFolderOfPicturesBecomesOnePieceWithAVariantEach() = runTest {
        val importer = importer()

        importer.start(
            zipOf(
                "Sunset.jpg" to photo(1600, 900, Color.rgb(230, 120, 20)),
                "Moss.png" to photo(900, 1600, Color.rgb(40, 160, 90)),
                "notes.txt" to "not a picture".toByteArray(),
            ),
        )
        val finished = importer.awaitFinished()

        assertEquals(1, finished.pieces)
        assertEquals(1, finished.skipped)
        val imported = importer.imported.value.single()
        assertEquals(ImportManifest.KIND_IMAGES, imported.manifest.kind)
        assertEquals(1, imported.catalogue.designs.size)
        assertEquals(2, imported.catalogue.remixes.size)
        assertEquals(listOf("Moss", "Sunset"), imported.catalogue.remixes.map { it.label }.sorted())
    }

    /** Square, or the shader's rotation would shear the picture rather than turn it. */
    @Test
    fun everyImportedPictureIsSquareAndWithinTheRenderersLimit() = runTest {
        val importer = importer()
        // Short edge above the master edge, so the cap is what decides the result.
        importer.start(zipOf("Wide.jpg" to photo(3600, 2400, Color.rgb(200, 40, 90))))
        importer.awaitFinished()

        val remix = importer.imported.value.single().catalogue.remixes.single()
        val layer = decodeBounds(remix.layers.single().imageUrl)
        assertEquals("must be square", layer.first, layer.second)
        assertEquals(ImageNormaliser.MASTER_EDGE, layer.first)
        val thumb = decodeBounds(remix.previews.thumb)
        assertEquals(ImageNormaliser.THUMB_EDGE, thumb.first)
        assertEquals(ImageNormaliser.THUMB_EDGE, thumb.second)
        // Square plus a rotation is the case SceneCoverage frames by the inscribed circle.
        assertNotNull(remix.layers.single().rotation)
    }

    /** Upscaling a small picture would only invent detail and cost memory. */
    @Test
    fun aPictureSmallerThanTheMasterEdgeIsNotEnlarged() = runTest {
        val importer = importer()
        importer.start(zipOf("Tiny.png" to photo(400, 300, Color.rgb(20, 40, 200))))
        importer.awaitFinished()

        val remix = importer.imported.value.single().catalogue.remixes.single()
        // The short edge, kept as it was: enlarging would only invent detail.
        assertEquals(300 to 300, decodeBounds(remix.layers.single().imageUrl))
    }

    @Test
    fun anImportedPictureCarriesAPaletteAndATone() = runTest {
        val importer = importer()
        importer.start(zipOf("Orange.jpg" to photo(800, 800, Color.rgb(230, 120, 20))))
        importer.awaitFinished()

        val remix = importer.imported.value.single().catalogue.remixes.single()
        assertNotNull(remix.colors.vibrantColor)
        val loading = remix.colors.loadingColor
        assertTrue("red should lead", Color.red(loading) > Color.blue(loading))
        assertEquals(ArtworkPalette.isDark(loading), remix.isDark)
    }

    /** The spin wrap rule applies to artwork the build never saw, exactly as to what it shipped. */
    @Test
    fun anImportedPictureObeysTheSpinWrapPeriod() = runTest {
        val importer = importer()
        importer.start(zipOf("Spin.png" to photo(600, 600, Color.rgb(90, 200, 160))))
        importer.awaitFinished()

        assertTrue(
            importer.imported.value.single().catalogue.remixes.all(
                BundledCatalogRepository::wrapsWithTheSpinPeriod,
            ),
        )
    }

    @Test
    fun theSamePicturesTwiceAreRecognisedRatherThanCopiedAgain() = runTest {
        val importer = importer()
        importer.start(zipOf("A.png" to photo(500, 500, Color.rgb(10, 90, 200))))
        importer.awaitFinished()
        importer.acknowledge()

        importer.start(zipOf("A.png" to photo(500, 500, Color.rgb(10, 90, 200))))
        val failed = importer.awaitFailure()

        assertTrue(failed, "already imported" in failed)
        assertEquals(1, importer.imported.value.size)
    }

    @Test
    fun aZipHoldingNoPicturesSaysSoRatherThanCommittingNothing() = runTest {
        val importer = importer()

        importer.start(zipOf("notes.txt" to "nothing here".toByteArray()))
        val failed = importer.awaitFailure()

        assertTrue(failed, "pictures" in failed)
        assertTrue(importer.imported.value.isEmpty())
    }

    @Test
    fun everyImportedFileLandsWhereItsCatalogueSaysItDoes() = runTest {
        val importer = importer()
        importer.start(
            zipOf(
                "One.jpg" to photo(2400, 1600, Color.rgb(200, 90, 40)),
                "Two.png" to photo(700, 700, Color.rgb(40, 90, 200)),
            ),
        )
        importer.awaitFinished()

        importer.imported.value.single().catalogue.remixes.forEach { remix ->
            (remix.layers.map { it.imageUrl } + remix.previews.thumb).forEach { path ->
                val source = ArtworkPaths.resolve(path, File(filesDir, "imports"))
                assertEquals(ImageSource.Kind.IMPORTED, source.kind)
                assertTrue(path, File(source.path).isFile)
            }
        }
    }

    /**
     * The whole point: a photograph the user picked has to draw.
     *
     * Run against the application's own store rather than this test's, because that is the only
     * root the renderer resolves imported paths against — so this is the real path from a file on
     * the phone to a GL texture, and it cleans up after itself.
     */
    @Test
    fun anImportedPictureRendersThroughTheRealRenderer() = runTest {
        val application = context.applicationContext as dev.paperouette.wallpaper.PaperouetteApplication
        val importer = application.importer
        val before = importer.imported.value.map { it.manifest.id }.toSet()
        try {
            importer.start(
                zipOf(
                    "One.jpg" to photo(2400, 1600, Color.rgb(200, 90, 40)),
                    "Two.png" to photo(700, 700, Color.rgb(40, 90, 200)),
                ),
            )
            val finished = importer.awaitFinished()
            val imported = importer.imported.value.single { it.manifest.id == finished.importId }

            OffscreenRenderer(context, width = 72, height = 128).use { renderer ->
                imported.catalogue.remixes.forEach(renderer::render)
            }
        } finally {
            importer.imported.value
                .filterNot { it.manifest.id in before }
                .forEach { importer.remove(it.manifest.id) }
            importer.acknowledge()
        }
    }

    private fun CoroutineScope.importer() =
        ArtworkImporter(store, this, usableSpace = { 1024L * 1024 * 1024 })

    private suspend fun ArtworkImporter.awaitEnd(): ImportProgress =
        progress.first { it is ImportProgress.Finished || it is ImportProgress.Failed }

    private suspend fun ArtworkImporter.awaitFinished(): ImportProgress.Finished =
        when (val end = awaitEnd()) {
            is ImportProgress.Finished -> end
            else -> throw AssertionError("import failed: ${(end as ImportProgress.Failed).reason}")
        }

    private suspend fun ArtworkImporter.awaitFailure(): String =
        when (val end = awaitEnd()) {
            is ImportProgress.Failed -> end.reason
            else -> throw AssertionError("import unexpectedly succeeded")
        }

    private fun decodeBounds(cataloguePath: String): Pair<Int, Int> {
        val file = File(ArtworkPaths.resolve(cataloguePath, File(filesDir, "imports")).path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** A picture with a diagonal in it, so a crop or a rotation would be visible if it were wrong. */
    private fun photo(width: Int, height: Int, tint: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val shade = ((x + y) * 255 / (width + height)).coerceIn(0, 255)
                bitmap.setPixel(
                    x,
                    y,
                    Color.rgb(
                        Color.red(tint) * shade / 255,
                        Color.green(tint) * shade / 255,
                        Color.blue(tint) * shade / 255,
                    ),
                )
            }
        }
        return ByteArrayOutputStream().also { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
        }.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ImportSource {
        val zip = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { out ->
                entries.forEach { (name, data) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(data)
                    out.closeEntry()
                }
            }
        }.toByteArray()
        return ZipImportSource("Holiday") { zip.inputStream() }
    }
}
