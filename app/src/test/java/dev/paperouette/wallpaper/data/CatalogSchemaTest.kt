package dev.paperouette.wallpaper.data

import dev.paperouette.wallpaper.model.Catalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSchemaTest {
    @Test
    fun bundledCatalogueHasExpectedOfflineShape() {
        val file = assetFile("catalog/catalog.json")
        val raw = file.readText()
        val catalogue = JSON.decodeFromString<Catalog>(raw)

        assertEquals(26, catalogue.designs.size)
        assertEquals(352, catalogue.remixes.size)
        assertEquals(728, catalogue.remixes.sumOf { it.layers.size })
        assertFalse(raw.contains("https://"))
        assertFalse(raw.contains("http://"))
        assertTrue(
            catalogue.remixes.flatMap { it.layers }.all {
                it.imageUrl.startsWith("assets/artwork/")
            },
        )
    }

    /**
     * The separator that keeps imports out of the bundled namespace.
     *
     * Imported ids are `import:<importId>:<sourceId>`, and the whole guarantee that one cannot
     * shadow a bundled piece rests on a colon appearing in no bundled id. It matters here more
     * than anywhere: the packs this is written for carry the same fourteen `spinner_*` design ids
     * and the same 208 remix ids that this catalogue kept.
     */
    @Test
    fun noBundledIdCarriesTheImportSeparator() {
        val catalogue = JSON.decodeFromString<Catalog>(assetFile("catalog/catalog.json").readText())

        val ids = catalogue.designs.map { it.id } + catalogue.remixes.map { it.id }
        assertTrue(
            "ids holding a colon: ${ids.filter { ':' in it }.take(4)}",
            ids.none { ':' in it },
        )
        assertTrue(ids.none { it.startsWith(ImportedCatalog.ID_PREFIX) })
    }

    /**
     * Every layer is resolved colour: the renderers shade and light in ways a ramp lookup cannot
     * express, so no layer carries a ramp and none is cyclic. A stray ramp would mean a layer
     * from the retired mask catalogue survived the swap, recolouring pixels that are already
     * coloured — worth asserting rather than leaving to the eye.
     */
    @Test
    fun everyLayerShipsResolvedColour() {
        val catalogue = JSON.decodeFromString<Catalog>(assetFile("catalog/catalog.json").readText())

        val ramped = catalogue.remixes
            .flatMap { remix -> remix.layers.map { remix.id to it } }
            .filter { (_, layer) -> !layer.ramp.isNullOrEmpty() || layer.cyclic }
        assertTrue("layers with a ramp: ${ramped.take(4).map { it.first }}", ramped.isEmpty())
    }

    /**
     * The invariants the theme pairing rests on, since it reads labels rather than ids.
     *
     * A "(Dark)" label with no base in its own design would be a half-pair the selection could
     * fall into and never leave; a label that takes part in a pair but appears twice would make
     * the twin ambiguous. Spinner 18 does carry two remixes labelled "Blue", which is why the
     * uniqueness claim is confined to labels that actually pair.
     */
    @Test
    fun everyDarkLabelIsPairedWithExactlyOneBaseInItsOwnDesign() {
        val catalogue = JSON.decodeFromString<Catalog>(assetFile("catalog/catalog.json").readText())

        val orphans = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        catalogue.remixes.groupBy { it.designId }.forEach { (_, remixes) ->
            val counts = remixes.groupingBy { it.label }.eachCount()
            remixes.filter { it.label.endsWith(DARK_SUFFIX) }.forEach { twin ->
                val base = twin.label.removeSuffix(DARK_SUFFIX)
                if (counts[base] == null) orphans += twin.id
                if (counts[base] != 1 || counts.getValue(twin.label) != 1) ambiguous += twin.id
            }
        }
        assertTrue("dark labels with no base: $orphans", orphans.isEmpty())
        assertTrue("labels that pair more than once: $ambiguous", ambiguous.isEmpty())

        // 79 pairs across the twelve layered designs and Spinner 32. The rest have no twin, which
        // is why following the system theme must leave them where they are rather than reset them.
        assertEquals(79, catalogue.remixes.count { it.label.endsWith(DARK_SUFFIX) })
    }

    @Test
    fun checksumManifestCoversEveryShippedFile() {
        val manifest = Json.parseToJsonElement(
            assetFile("catalog/checksums.json").readText(),
        ).jsonObject
        val counts = manifest.getValue("counts").jsonObject

        assertEquals(26, counts.int("designs"))
        assertEquals(352, counts.int("remixes"))
        assertEquals(728, counts.int("layers"))
        // One image per layer reference plus one thumb per variant, less the sharing among
        // effect variants, whose layers deduplicate by content address.
        assertEquals(948, counts.int("uniqueAssetFiles"))
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.int(key: String): Int =
        getValue(key).jsonPrimitive.content.toInt()

    private fun assetFile(relative: String): File {
        val fromModule = File("src/main/assets", relative)
        return if (fromModule.isFile) fromModule else File("app/src/main/assets", relative)
    }

    private companion object {
        const val DARK_SUFFIX = " (Dark)"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
