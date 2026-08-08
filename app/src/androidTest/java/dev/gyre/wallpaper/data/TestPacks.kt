package dev.gyre.wallpaper.data

import android.content.Context
import android.graphics.BitmapFactory
import dev.gyre.wallpaper.model.Catalog
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Packs built the way `tools/export_pack.py` builds them, cut from the bundled catalogue.
 *
 * Made here rather than committed: the artwork is already on the device under test, so a fixture
 * exercises the real catalogue shape without a binary in the repository.
 */
object TestPacks {
    /**
     * A pack cut from two remixes of the bundled catalogue, laid out as the exporter lays one out.
     *
     * [corruptOneAsset] flips a byte so the declared digest no longer matches; [smuggle] adds an
     * entry the manifest never declared, under a traversing name.
     */
    fun build(
        context: Context,
        corruptOneAsset: Boolean = false,
        smuggle: String? = null,
    ): ByteArray {
        val bundled = Json { ignoreUnknownKeys = true }
            .decodeFromString<Catalog>(
                context.assets.open("catalog/catalog.json").bufferedReader().use { it.readText() },
            )
        val design = bundled.designs.first { it.remixIds.size >= 2 }
        val remixes = design.remixIds.take(2).map { id -> bundled.remixes.first { it.id == id } }
        val subset = Catalog(
            designIds = listOf(design.id),
            remixIds = remixes.map { it.id },
            designs = listOf(
                design.copy(remixIds = remixes.map { it.id }, previewRemixId = remixes.first().id),
            ),
            remixes = remixes,
        )
        val paths = remixes.flatMap { remix ->
            remix.layers.map { it.imageUrl } + remix.previews.thumb
        }.distinct().sorted()

        val blobs = paths.associateWith { path ->
            context.assets.open(path.removePrefix("assets/")).use { it.readBytes() }
        }
        val manifest = PackManifest(
            formatVersion = PackManifest.SUPPORTED_VERSION,
            name = "Fixture",
            packId = FIXTURE_PACK_ID,
            counts = PackCounts(
                designs = 1,
                remixes = remixes.size,
                layers = remixes.sumOf { it.layers.size },
                assets = paths.size,
            ),
            totalBytes = blobs.values.sumOf { it.size }.toLong(),
            assets = paths.map { path ->
                val data = blobs.getValue(path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                PackAsset(
                    path = "artwork/${path.removePrefix("assets/artwork/")}",
                    sha256 = sha256(data),
                    bytes = data.size.toLong(),
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                )
            },
        )
        val catalogueJson = Json.encodeToString(Catalog.serializer(), subset)

        return ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("gyre-pack.json"))
                zip.write(Json.encodeToString(PackManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("catalog/catalog.json"))
                zip.write(catalogueJson.toByteArray())
                zip.closeEntry()
                if (smuggle != null) {
                    zip.putNextEntry(ZipEntry(smuggle))
                    zip.write(ByteArray(32) { 0x7f })
                    zip.closeEntry()
                }
                paths.forEachIndexed { index, path ->
                    val data = blobs.getValue(path).copyOf()
                    if (corruptOneAsset && index == 0) data[data.size - 1] = (data.last() + 1).toByte()
                    zip.putNextEntry(ZipEntry("artwork/${path.removePrefix("assets/artwork/")}"))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun sha256(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }


    /** Constant, so the same fixture built twice is recognised as the same pack. */
    const val FIXTURE_PACK_ID = "fixture000000001"
}
