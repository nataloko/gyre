package dev.paperouette.wallpaper.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import pipeline, end to end, against packs built the way `tools/export_pack.py` builds them.
 *
 * The fixtures are made here rather than committed: the bundled catalogue is already on the device
 * under test, so a pack cut from two of its remixes exercises the real catalogue shape without a
 * binary in the repository. The picker never appears — [ImportSource] is the seam it stops at, so
 * none of this needs a ContentProvider the release check would refuse.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var filesDir: File
    private lateinit var store: ImportStore

    @Before
    fun setUp() {
        filesDir = File(context.cacheDir, "import-test-${System.nanoTime()}").apply { mkdirs() }
        store = ImportStore(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun aPackBecomesPiecesAtTheEndOfTheCollection() = runTest {
        val pack = TestPacks.build(context)
        val importer = importer()

        importer.start(ZipImportSource("fixture.zip") { pack.inputStream() })
        val finished = importer.awaitFinished()

        assertEquals(0, finished.skipped)
        val imported = importer.imported.value.single()
        assertEquals(2, imported.manifest.remixes)
        assertTrue(imported.manifest.bytes > 0)

        // Every id namespaced, and every layer resolvable to a file that is actually there.
        imported.catalogue.remixes.forEach { remix ->
            assertTrue(remix.id, remix.id.startsWith(ImportedCatalog.ID_PREFIX))
            (remix.layers.map { it.imageUrl } + remix.previews.thumb).forEach { path ->
                val source = ArtworkPaths.resolve(path, File(filesDir, "imports"))
                assertEquals(ImageSource.Kind.IMPORTED, source.kind)
                assertTrue(path, File(source.path).isFile)
            }
        }
    }

    /** Bundled first: an import must not renumber the collection the user already knows. */
    @Test
    fun theComposedCatalogueKeepsTheBundledPiecesFirst() = runTest {
        val bundled = BundledCatalogRepository(context)
        val importer = importer()
        val catalogue = PaperouetteCatalogRepository(bundled, importer.imported, backgroundScope)
        val before = catalogue.current.value.designs

        importer.start(ZipImportSource("fixture.zip") { TestPacks.build(context).inputStream() })
        importer.awaitFinished()
        val after = catalogue.current.first { it.designs.size > before.size }

        assertEquals(before.map { it.id }, after.designs.take(before.size).map { it.id })
        assertTrue(after.designs.last().id.startsWith(ImportedCatalog.ID_PREFIX))
    }

    @Test
    fun theSamePackTwiceIsRecognisedRatherThanCopiedAgain() = runTest {
        val importer = importer()
        importer.start(ZipImportSource("fixture.zip") { TestPacks.build(context).inputStream() })
        importer.awaitFinished()
        importer.acknowledge()

        importer.start(ZipImportSource("fixture.zip") { TestPacks.build(context).inputStream() })
        val second = importer.awaitFailure()

        assertTrue(second, "already imported" in second)
        assertEquals(1, importer.imported.value.size)
    }

    @Test
    fun aTamperedFileAbortsTheWholeImport() = runTest {
        val importer = importer()

        importer.start(
            ZipImportSource("fixture.zip") {
                TestPacks.build(context, corruptOneAsset = true).inputStream()
            },
        )
        val failed = importer.awaitFailure()

        assertTrue(failed, "checksum" in failed)
        assertTrue(importer.imported.value.isEmpty())
        // Nothing half-written survives, under imports or staging.
        assertFalse(File(filesDir, "imports").listFiles().orEmpty().any { it.isDirectory })
        assertTrue(File(filesDir, "imports.staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aForgedPackIdentityIsRefusedBeforeStagingExists() = runTest {
        val importer = importer()
        val pack = TestPacks.build(
            context,
            transformManifest = { it.copy(packId = "0".repeat(16)) },
        )

        importer.start(ZipImportSource("forged.zip") { pack.inputStream() })
        val failed = importer.awaitFailure()

        assertTrue(failed, "identity" in failed)
        assertFalse(File(filesDir, "imports.staging").exists())
    }

    @Test
    fun copiedPicturesMustMatchTheirDeclaredDimensions() = runTest {
        val importer = importer()
        val pack = TestPacks.build(
            context,
            transformManifest = { manifest ->
                manifest.copy(
                    assets = manifest.assets.mapIndexed { index, asset ->
                        if (index == 0) asset.copy(width = asset.width + 1) else asset
                    },
                )
            },
        )

        importer.start(ZipImportSource("dimensions.zip") { pack.inputStream() })
        val failed = importer.awaitFailure()

        assertTrue(failed, "dimensions" in failed)
        assertTrue(importer.imported.value.isEmpty())
        assertTrue(File(filesDir, "imports.staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun metadataAndPictureStreamsStopAtTheirLimits() = runTest {
        val manifestReads = CountingStream(4 * MEBIBYTE + COPY_BUFFER)
        val manifestImporter = importer()
        manifestImporter.start(
            EntriesSource(
                listOf(ImportSource.Entry(PackManifest.PATH, -1, manifestReads)),
            ),
        )
        assertTrue("manifest is too large" in manifestImporter.awaitFailure())
        assertTrue(manifestReads.read <= 4 * MEBIBYTE + COPY_BUFFER)

        val emptyManifest = PackManifest(
            formatVersion = 1,
            name = "Large catalogue",
            packId = "0123456789abcdef",
            counts = PackCounts(0, 0, 0, 0),
            totalBytes = 0,
            assets = emptyList(),
        )
        val catalogueReads = CountingStream(8 * MEBIBYTE + COPY_BUFFER)
        val catalogueImporter = importer()
        catalogueImporter.start(
            EntriesSource(
                listOf(
                    entry(PackManifest.PATH, Json.encodeToString(PackManifest.serializer(), emptyManifest)),
                    ImportSource.Entry(PackManifest.CATALOGUE_PATH, -1, catalogueReads),
                ),
            ),
        )
        assertTrue("catalogue is too large" in catalogueImporter.awaitFailure())
        assertTrue(catalogueReads.read <= 8 * MEBIBYTE + COPY_BUFFER)

        val pictureReads = CountingStream(64 * MEBIBYTE + COPY_BUFFER)
        val pictureImporter = importer()
        pictureImporter.start(EntriesSource(listOf(ImportSource.Entry("huge.jpg", -1, pictureReads))))
        assertTrue("picture" in pictureImporter.awaitFailure())
        assertTrue(pictureReads.read <= 64 * MEBIBYTE + COPY_BUFFER)
    }

    @Test
    fun simultaneousStartsAreRejectedInsteadOfQueued() = runTest {
        val importer = importer()
        val first = EntriesSource(emptyList())
        val rejected = EntriesSource(emptyList())

        assertTrue(importer.start(first))
        assertFalse(importer.start(rejected))
        assertTrue(rejected.closed)
        importer.cancel()
    }

    @Test
    fun cancellationClosesTheSourceAndCleansStaging() = runTest {
        val importer = importer()
        val entries = zipEntries(TestPacks.build(context))
        val blocking = BlockingStream()
        val source = EntriesSource(
            entries.take(3) + ImportSource.Entry(entries[3].name, entries[3].size, blocking),
        )

        importer.start(source)
        blocking.opened.await()
        importer.cancel()
        source.closedSignal.await()
        blocking.closedSignal.await()
        withTimeout(5_000) {
            while (File(filesDir, "imports.staging").listFiles().orEmpty().isNotEmpty()) yield()
        }

        assertTrue(source.closed)
        assertTrue(importer.imported.value.isEmpty())
        assertTrue(File(filesDir, "imports.staging").listFiles().orEmpty().isEmpty())
    }

    /**
     * The importer writes only what the manifest declared, so an entry name never reaches the
     * filesystem — which is what makes the folder importer safe too, having no ZipInputStream
     * beneath it to refuse a name on its behalf.
     */
    @Test
    fun anEntryTheManifestNeverDeclaredIsIgnored() = runTest {
        val importer = importer()

        importer.start(
            ZipImportSource("fixture.zip") { TestPacks.build(context, smuggle = "unlisted.webp").inputStream() }
        )
        importer.awaitFinished()

        val imported = importer.imported.value.single()
        assertEquals(imported.manifest.files, store.directoryFor(imported.manifest.id).artworkCount())
        assertFalse(File(store.directoryFor(imported.manifest.id), "artwork/unlisted.webp").exists())
    }

    /** An archive built to write outside its own directory is not one to read the rest of. */
    @Test
    fun aTraversingEntryNameStopsTheImport() = runTest {
        val importer = importer()

        importer.start(
            ZipImportSource("fixture.zip") { TestPacks.build(context, smuggle = "../../../evil.webp").inputStream() }
        )
        val failed = importer.awaitFailure()

        assertTrue(failed, "not safe to open" in failed)
        assertTrue(importer.imported.value.isEmpty())
        assertFalse(File(filesDir.parentFile, "evil.webp").exists())
        assertTrue(File(filesDir, "imports.staging").listFiles().orEmpty().isEmpty())
    }

    private fun File.artworkCount() = File(this, "artwork").listFiles().orEmpty().size

    /**
     * A zip that is not a pack is read as pictures, and says what it could not read.
     *
     * Which kind of import this is comes from the first entry rather than from the user: a zip of
     * holiday photos and a zip of catalogued artwork are the same gesture, and only one of them is
     * a thing anybody knows the name of.
     */
    @Test
    fun aZipThatIsNotAPackIsReadAsPictures() = runTest {
        val importer = importer()
        val notAPack = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("holiday.jpg"))
                zip.write(ByteArray(16))
                zip.closeEntry()
            }
        }.toByteArray()

        importer.start(ZipImportSource("holiday.zip") { notAPack.inputStream() })
        val failed = importer.awaitFailure()

        assertTrue(failed, "could be read as pictures" in failed)
        assertTrue(importer.imported.value.isEmpty())
    }

    /** A pack that announces itself and then cannot be read says so as a pack, not as pictures. */
    @Test
    fun aPackWithAnUnreadableManifestSaysSoAsAPack() = runTest {
        val importer = importer()
        val broken = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry(PackManifest.PATH))
                zip.write("{ not json".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        importer.start(ZipImportSource("broken.zip") { broken.inputStream() })
        val failed = importer.awaitFailure()

        assertTrue(failed, "manifest could not be read" in failed)
    }

    @Test
    fun aPackTooLargeForTheDiskIsRefusedBeforeAnythingIsWritten() = runTest {
        val importer = ArtworkImporter(store, backgroundScope, usableSpace = { 1L })

        importer.start(ZipImportSource("fixture.zip") { TestPacks.build(context).inputStream() })
        val failed = importer.awaitFailure()

        assertTrue(failed, "space" in failed)
        assertTrue(File(filesDir, "imports.staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun removingAnImportTakesItsArtworkWithIt() = runTest {
        val importer = importer()
        importer.start(ZipImportSource("fixture.zip") { TestPacks.build(context).inputStream() })
        val finished = importer.awaitFinished()

        val directory = store.directoryFor(finished.importId)
        assertTrue(directory.isDirectory)
        importer.remove(finished.importId)

        assertTrue(importer.imported.value.isEmpty())
        assertFalse(directory.exists())
        assertTrue(File(filesDir, "imports.staging").listFiles().orEmpty().isEmpty())
    }

    /** An interrupted import must not surface as a design whose artwork was never written. */
    @Test
    fun debrisFromAnInterruptedImportIsSweptBeforeAnythingReadsIt() = runTest {
        File(filesDir, "imports/half-written/artwork").mkdirs()
        File(filesDir, "imports.staging/abandoned").mkdirs()

        val importer = importer()

        assertTrue(importer.imported.value.isEmpty())
        assertFalse(File(filesDir, "imports/half-written").exists())
        assertFalse(File(filesDir, "imports.staging/abandoned").exists())
    }

    /** A committed import is read back from disk alone, as it will be on the next launch. */
    @Test
    fun anImportSurvivesTheProcessThatMadeIt() = runTest {
        importer().let { first ->
            first.start(ZipImportSource("fixture.zip") { TestPacks.build(context).inputStream() })
            first.awaitFinished()
        }

        val reopened = ArtworkImporter(ImportStore(filesDir), backgroundScope, usableSpace = { GIGABYTE })

        val imported = reopened.imported.value.single()
        assertEquals(2, imported.catalogue.remixes.size)
        assertNotNull(imported.catalogue.designs.single().previewRemixId)
    }

    private fun kotlinx.coroutines.CoroutineScope.importer() =
        ArtworkImporter(store, this, usableSpace = { GIGABYTE })

    /**
     * Waits for the import to end either way.
     *
     * Never for one outcome alone: waiting only for success turns a failure into a minute of
     * silence and a timeout that says nothing about why.
     */
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

    private fun entry(name: String, json: String) =
        ImportSource.Entry(name, json.length.toLong(), json.encodeToByteArray().inputStream())

    private fun zipEntries(bytes: ByteArray): List<ImportSource.Entry> {
        val entries = mutableListOf<ImportSource.Entry>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                if (!next.isDirectory) {
                    val data = zip.readBytes()
                    entries += ImportSource.Entry(next.name, data.size.toLong(), data.inputStream())
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private class EntriesSource(private val entries: List<ImportSource.Entry>) : ImportSource {
        override val label = "Fixture"
        var closed = false
            private set
        val closedSignal = CompletableDeferred<Unit>()

        override fun forEachEntry(visit: (ImportSource.Entry) -> Unit) = entries.forEach(visit)

        override fun close() {
            if (closed) return
            closed = true
            entries.forEach { runCatching { it.stream.close() } }
            closedSignal.complete(Unit)
        }
    }

    private class CountingStream(private val length: Int) : InputStream() {
        var read = 0
            private set

        override fun read(): Int = if (read++ < length) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (read >= this.length) return -1
            val count = minOf(length, this.length - read)
            buffer.fill(0, offset, offset + count)
            read += count
            return count
        }
    }

    private class BlockingStream : InputStream() {
        val opened = CompletableDeferred<Unit>()
        val closedSignal = CompletableDeferred<Unit>()

        override fun read(): Int {
            opened.complete(Unit)
            runBlockingUntilClosed()
            return -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            closedSignal.complete(Unit)
        }

        private fun runBlockingUntilClosed() = runBlocking { closedSignal.await() }
    }

    private companion object {
        const val GIGABYTE = 1024L * 1024 * 1024
        const val MEBIBYTE = 1024 * 1024
        const val COPY_BUFFER = 64 * 1024
    }
}
