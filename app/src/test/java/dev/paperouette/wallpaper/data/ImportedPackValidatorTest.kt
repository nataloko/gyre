package dev.paperouette.wallpaper.data

import dev.paperouette.wallpaper.model.Catalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Layer
import dev.paperouette.wallpaper.model.PaletteColors
import dev.paperouette.wallpaper.model.PreviewAssets
import dev.paperouette.wallpaper.model.Remix
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedPackValidatorTest {
    private val catalogue = catalogue()
    private val catalogueBytes = JSON.encodeToString(Catalog.serializer(), catalogue).encodeToByteArray()
    private val asset = asset('a')

    @Test
    fun aPackIdMustBeLowercaseHexOfTheExportersLength() {
        listOf("../outside", "ABCDEF0123456789", "abc", "gggggggggggggggg").forEach { id ->
            assertThrows(ImportException::class.java) {
                ImportedPackValidator.validateManifest(manifest(packId = id))
            }
        }
    }

    @Test
    fun theDeclaredTotalMustEqualTheOverflowSafeAssetSum() {
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateManifest(manifest(totalBytes = asset.bytes + 1))
        }
        val overflowing = manifest(
            assets = listOf(
                asset.copy(bytes = Long.MAX_VALUE),
                asset('b').copy(bytes = Long.MAX_VALUE),
            ),
            totalBytes = 1,
        )
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateManifest(overflowing)
        }
    }

    @Test
    fun assetPathsAreUniqueContentAddressesWithBoundedDimensions() {
        val duplicate = manifest(assets = listOf(asset, asset), totalBytes = asset.bytes * 2)
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateManifest(duplicate)
        }
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateManifest(
                manifest(assets = listOf(asset.copy(width = 4097))),
            )
        }
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateManifest(
                manifest(assets = listOf(asset.copy(sha256 = "b".repeat(64)))),
            )
        }
    }

    @Test
    fun thePackIdentityCoversAssetDigestsAndExactCatalogueJson() {
        val valid = manifest(packId = identity(listOf(asset), catalogueBytes))
        ImportedPackValidator.requireIdentity(valid, catalogueBytes)

        assertThrows(ImportException::class.java) {
            ImportedPackValidator.requireIdentity(valid, catalogueBytes + ' '.code.toByte())
        }
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.requireIdentity(valid.copy(packId = "0".repeat(16)), catalogueBytes)
        }
    }

    @Test
    fun catalogueIdsAndOrderEntriesCannotRepeat() {
        val repeatedOrder = catalogue.copy(designIds = listOf("piece", "piece"))
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateCatalogue(repeatedOrder, manifest())
        }
        val repeatedRemix = catalogue.copy(remixes = listOf(catalogue.remixes.single(), catalogue.remixes.single()))
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateCatalogue(
                repeatedRemix,
                manifest(counts = counts(remixes = 2, layers = 2)),
            )
        }
    }

    @Test
    fun everyVariantMustBelongToThePieceThatListsIt() {
        val broken = catalogue.copy(
            remixes = listOf(catalogue.remixes.single().copy(designId = "missing")),
        )
        assertThrows(ImportException::class.java) {
            ImportedPackValidator.validateCatalogue(broken, manifest())
        }
    }

    private fun manifest(
        packId: String = "0123456789abcdef",
        counts: PackCounts = counts(),
        totalBytes: Long = asset.bytes,
        assets: List<PackAsset> = listOf(asset),
    ) = PackManifest(
        formatVersion = PackManifest.SUPPORTED_VERSION,
        name = "Fixture",
        packId = packId,
        counts = counts.copy(assets = assets.size),
        totalBytes = totalBytes,
        assets = assets,
    )

    private fun counts(remixes: Int = 1, layers: Int = 1) =
        PackCounts(designs = 1, remixes = remixes, layers = layers, assets = 1)

    private fun asset(fill: Char) = PackAsset(
        path = "artwork/${fill.toString().repeat(64)}.webp",
        sha256 = fill.toString().repeat(64),
        bytes = 128,
        width = 32,
        height = 32,
    )

    private fun catalogue(): Catalog {
        val remix = Remix(
            id = "variant",
            designId = "piece",
            label = "Variant",
            isDark = false,
            isMultilayered = false,
            type = "parallax",
            previews = PreviewAssets("assets/artwork/${"a".repeat(64)}.webp"),
            layers = listOf(Layer("assets/artwork/${"a".repeat(64)}.webp", "animated")),
            colors = PaletteColors(loadingColor = 0),
        )
        val design = Design("piece", "Piece", remix.id, listOf(remix.id))
        return Catalog(listOf(design.id), listOf(remix.id), listOf(design), listOf(remix))
    }

    private fun identity(assets: List<PackAsset>, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.forEach { digest.update(it.sha256.encodeToByteArray()) }
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private companion object {
        val JSON = kotlinx.serialization.json.Json
    }
}
