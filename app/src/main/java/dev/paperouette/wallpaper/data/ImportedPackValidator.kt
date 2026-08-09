package dev.paperouette.wallpaper.data

import dev.paperouette.wallpaper.model.Catalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Remix
import java.security.MessageDigest

/** Structural checks for a pack, kept separate from the Android-dependent copy pipeline. */
internal object ImportedPackValidator {
    const val MAX_ASSET_BYTES = 64L * 1024 * 1024
    const val MAX_EDGE = 4096

    fun validateManifest(manifest: PackManifest) {
        rejectUnless(manifest.formatVersion == PackManifest.SUPPORTED_VERSION) {
            "That pack needs a newer version of Paperouette"
        }
        rejectUnless(manifest.kind == "pack") { "That pack's manifest disagrees with itself" }
        rejectUnless(PACK_ID.matches(manifest.packId)) { "That pack has an invalid identity" }
        rejectUnless(
            manifest.counts.designs >= 0 &&
                manifest.counts.remixes >= 0 &&
                manifest.counts.layers >= 0 &&
                manifest.counts.assets >= 0,
        ) { "That pack's manifest disagrees with itself" }
        rejectUnless(manifest.assets.size == manifest.counts.assets) {
            "That pack's manifest disagrees with itself"
        }

        val paths = mutableSetOf<String>()
        var total = 0L
        manifest.assets.forEach { asset ->
            rejectUnless(paths.add(asset.path)) { "That pack declares the same file twice" }
            val path = ASSET_PATH.matchEntire(asset.path)
            rejectUnless(path != null && DIGEST.matches(asset.sha256)) {
                "That pack declares an invalid artwork file"
            }
            rejectUnless(requireNotNull(path).groupValues[1] == asset.sha256) {
                "That pack's artwork name does not match its checksum"
            }
            total = try {
                Math.addExact(total, asset.bytes)
            } catch (_: ArithmeticException) {
                throw ImportException("That pack's declared size is invalid")
            }
            rejectUnless(asset.bytes in 1..MAX_ASSET_BYTES) {
                "That pack holds a file too large to import"
            }
            rejectUnless(asset.width in 1..MAX_EDGE && asset.height in 1..MAX_EDGE) {
                "That pack declares invalid picture dimensions"
            }
        }
        rejectUnless(total == manifest.totalBytes) {
            "That pack's declared size disagrees with its files"
        }
    }

    fun validateCatalogue(catalogue: Catalog, manifest: PackManifest) {
        rejectUnless(
            catalogue.designs.size == manifest.counts.designs &&
                catalogue.remixes.size == manifest.counts.remixes &&
                catalogue.remixes.sumOf { it.layers.size } == manifest.counts.layers,
        ) { "That pack's catalogue disagrees with its manifest" }

        rejectUnless(catalogue.designIds.isUnique() && catalogue.remixIds.isUnique()) {
            "That pack's catalogue repeats an order entry"
        }
        rejectUnless(catalogue.designs.map(Design::id).isUnique()) {
            "That pack's catalogue repeats a piece id"
        }
        rejectUnless(catalogue.remixes.map(Remix::id).isUnique()) {
            "That pack's catalogue repeats a variant id"
        }

        val designs = catalogue.designs.associateBy(Design::id)
        val remixes = catalogue.remixes.associateBy(Remix::id)
        rejectUnless(catalogue.designIds.size == designs.size && catalogue.designIds.toSet() == designs.keys) {
            "That pack's piece order is incomplete"
        }
        rejectUnless(catalogue.remixIds.size == remixes.size && catalogue.remixIds.toSet() == remixes.keys) {
            "That pack's variant order is incomplete"
        }

        val claimedRemixes = mutableSetOf<String>()
        catalogue.designs.forEach { design ->
            rejectUnless(design.remixIds.isUnique()) {
                "That pack's piece repeats a variant"
            }
            rejectUnless(design.previewRemixId in design.remixIds) {
                "That pack's piece has a broken preview"
            }
            design.remixIds.forEach { remixId ->
                val remix = remixes[remixId]
                rejectUnless(remix != null && remix.designId == design.id && claimedRemixes.add(remixId)) {
                    "That pack has a broken piece and variant relationship"
                }
            }
        }
        rejectUnless(claimedRemixes == remixes.keys && catalogue.remixes.all { it.designId in designs }) {
            "That pack has a broken piece and variant relationship"
        }
    }

    /** The exporter hashes asset digests in manifest order, followed by the exact catalogue bytes. */
    fun requireIdentity(manifest: PackManifest, catalogueBytes: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256")
        manifest.assets.forEach { digest.update(it.sha256.encodeToByteArray()) }
        digest.update(catalogueBytes)
        rejectUnless(digest.digest().toHex().take(PACK_ID_LENGTH) == manifest.packId) {
            "That pack's identity does not match its contents"
        }
    }

    private fun List<String>.isUnique() = size == toSet().size

    private inline fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) throw ImportException(message())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val PACK_ID_LENGTH = 16
    private val PACK_ID = Regex("[0-9a-f]{$PACK_ID_LENGTH}")
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val ASSET_PATH = Regex("artwork/([0-9a-f]{64})\\.(webp|png|jpg|jpeg)")
}
