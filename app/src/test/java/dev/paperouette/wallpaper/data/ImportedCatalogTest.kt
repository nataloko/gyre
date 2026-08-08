package dev.paperouette.wallpaper.data

import dev.paperouette.wallpaper.model.Catalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Layer
import dev.paperouette.wallpaper.model.PaletteColors
import dev.paperouette.wallpaper.model.PreviewAssets
import dev.paperouette.wallpaper.model.Remix
import dev.paperouette.wallpaper.model.RotationDirection
import dev.paperouette.wallpaper.model.RotationSpec
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules an imported catalogue is held to.
 *
 * A pack is a file people hand around, so nothing it claims is taken on trust: what cannot be
 * drawn correctly is dropped or defanged here, once, rather than left to fail on the stage.
 */
class ImportedCatalogTest {
    private val square = digest('a')
    private val wide = digest('b')
    private val thumb = digest('c')
    private val available = setOf(square, wide, thumb)
    private val sizes = mapOf(square to (2048 to 2048), wide to (2600 to 2413), thumb to (260 to 260))

    private fun namespaced(catalogue: Catalog, importId: String = "pack01") =
        ImportedCatalog.namespaced(
            importId = importId,
            catalogue = catalogue,
            available = available,
            sizeOf = { path -> ImportedCatalog.fileNameOf(path)?.let(sizes::get) },
        )

    @Test
    fun everyIdAndPathIsRewrittenIntoTheImportsNamespace() {
        val result = namespaced(catalogueOf(remix("piece", "moss", square)))

        val design = result.snapshot.designs.single()
        val variant = result.snapshot.remixes.single()
        assertEquals("import:pack01:piece", design.id)
        assertEquals("import:pack01:moss", variant.id)
        assertEquals("import:pack01:piece", variant.designId)
        assertEquals(listOf("import:pack01:moss"), design.remixIds)
        assertEquals("import:pack01:moss", design.previewRemixId)
        assertEquals("imported/pack01/artwork/$square", variant.layers.single().imageUrl)
        assertEquals("imported/pack01/artwork/$thumb", variant.previews.thumb)
        // The rewritten paths must be resolvable, or the artwork is named but unreachable.
        assertEquals(
            ImageSource.Kind.IMPORTED,
            ArtworkPaths.resolve(variant.layers.single().imageUrl, File("/tmp/imports")).kind,
        )
        assertEquals("Moss", variant.label)
        assertEquals(0, result.skipped)
    }

    /**
     * The collision this exists to prevent.
     *
     * The bundled catalogue kept every `spinner_*` design id and every `*_color_N` remix id from
     * the artwork these packs are made of, so an unprefixed import would shadow all of them.
     */
    @Test
    fun anImportCannotCollideWithABundledId() {
        val result = namespaced(catalogueOf(remix("spinner_7", "spinner_7_color_1", square)))

        assertTrue(result.snapshot.designs.single().id.startsWith(ImportedCatalog.ID_PREFIX))
        assertTrue(result.snapshot.remixes.all { ':' in it.id })
        assertEquals("pack01", ImportedCatalog.importIdOf(result.snapshot.designs.single().id))
        assertNull(ImportedCatalog.importIdOf("spinner_7"))
    }

    /**
     * The shader turns the scene in normalised coordinates, so rotating a non-square base layer
     * shears it. Held still it is cover-fitted and correct, which is the better of the two.
     */
    @Test
    fun aNonSquareBaseLayerLosesItsRotation() {
        val result = namespaced(catalogueOf(remix("piece", "wide", wide)))

        assertNull(result.snapshot.remixes.single().layers.single().rotation)
        assertEquals(0, result.skipped)
    }

    /**
     * SceneCoverage frames a static base by cover-fit and reserves it no headroom, so panning
     * one would slide the window past the edge of the artwork.
     */
    @Test
    fun aStaticBaseLayerLosesItsParallax() {
        val still = remix("piece", "still", square).let { source ->
            source.copy(
                layers = listOf(source.layers.single().copy(rotation = null, parallaxScale = 0.4f)),
            )
        }

        assertEquals(0f, namespaced(catalogueOf(still)).snapshot.remixes.single().layers.single().parallaxScale)
    }

    @Test
    fun aRemixWhoseSpinDoesNotWrapIsDropped() {
        val result = namespaced(
            catalogueOf(
                remix("piece", "good", square),
                remix("piece", "bad", square).copy(inputRotationScaler = 0.3f),
            ),
        )

        assertEquals(listOf("import:pack01:good"), result.snapshot.remixes.map { it.id })
        assertEquals(1, result.skipped)
    }

    @Test
    fun aRemixNamingArtworkThePackDidNotShipIsDropped() {
        val result = namespaced(catalogueOf(remix("piece", "ghost", digest('f'))))

        assertTrue(result.snapshot.remixes.isEmpty())
        assertTrue(result.snapshot.designs.isEmpty())
        assertEquals(1, result.skipped)
    }

    /** A design left with nothing to show would divide by zero when browsed. */
    @Test
    fun aDesignLeftWithNoRemixesGoesWithThem() {
        val result = namespaced(
            catalogueOf(
                remix("kept", "fine", square),
                remix("emptied", "broken", square).copy(inputRotationScaler = 0.3f),
            ),
        )

        assertEquals(listOf("import:pack01:kept"), result.snapshot.designs.map { it.id })
    }

    /** A preview naming a dropped remix must fall back rather than dangle. */
    @Test
    fun aDesignWhosePreviewWasDroppedPointsAtWhatSurvived() {
        val catalogue = catalogueOf(
            remix("piece", "gone", square).copy(inputRotationScaler = 0.3f),
            remix("piece", "kept", square),
        )

        val design = namespaced(catalogue).snapshot.designs.single()
        assertEquals("import:pack01:kept", design.previewRemixId)
        assertNotNull(namespaced(catalogue).snapshot.remixOrNull(design.previewRemixId))
    }

    @Test
    fun aTraversingArtworkPathNamesNoFile() {
        listOf(
            "assets/artwork/../../../etc/passwd",
            "../../etc/passwd",
            "/etc/passwd",
            "assets/artwork/notadigest.webp",
            "https://example.invalid/a.webp",
        ).forEach { assertNull(it, ImportedCatalog.fileNameOf(it)) }
    }

    /** What the store writes has to be what it can read back. */
    @Test
    fun theRewrittenCatalogueRoundTripsThroughItsOwnJson() {
        val snapshot = namespaced(catalogueOf(remix("piece", "moss", square))).snapshot
        val json = Json.encodeToString(Catalog.serializer(), snapshot.asCatalog())
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<Catalog>(json)

        assertEquals(snapshot.designs.map(Design::id), parsed.designIds)
        assertEquals(snapshot.remixes.map(Remix::id), parsed.remixIds)
    }

    private fun digest(fill: Char) = "$fill".repeat(64) + ".webp"

    private fun catalogueOf(vararg remixes: Remix): Catalog {
        val designs = remixes.groupBy(Remix::designId).map { (designId, own) ->
            Design(
                id = designId,
                label = designId.replaceFirstChar(Char::uppercase),
                previewRemixId = own.first().id,
                remixIds = own.map(Remix::id),
            )
        }
        return Catalog(
            designIds = designs.map(Design::id),
            remixIds = remixes.map(Remix::id),
            designs = designs,
            remixes = remixes.toList(),
        )
    }

    private fun remix(designId: String, id: String, layer: String) = Remix(
        id = id,
        designId = designId,
        label = id.replaceFirstChar(Char::uppercase),
        isDark = false,
        isMultilayered = false,
        inputRotationScaler = 0.75f,
        type = "parallax",
        previews = PreviewAssets("assets/artwork/$thumb"),
        layers = listOf(
            Layer(
                imageUrl = "assets/artwork/$layer",
                type = "animated",
                parallaxScale = 0.4f,
                parallaxOnlyOnTilt = true,
                rotation = RotationSpec(200f, RotationDirection.CLOCKWISE),
            ),
        ),
        colors = PaletteColors(loadingColor = -16777216),
    )
}
