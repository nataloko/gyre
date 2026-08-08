package dev.gyre.wallpaper.data

import kotlinx.serialization.Serializable

/**
 * The `gyre-pack.json` a pack carries, written by `tools/export_pack.py`.
 *
 * It is read before anything it describes, which is the point of it: the app learns the complete
 * legal set of entries, their digests and their exact sizes up front, so it can check free space,
 * refuse a doctored archive, and know whether a rotating layer is square without decoding it.
 */
@Serializable
data class PackManifest(
    val formatVersion: Int,
    val name: String,
    val packId: String,
    val counts: PackCounts,
    val totalBytes: Long,
    val assets: List<PackAsset>,
    val kind: String = "pack",
    val spinWrapTurns: Int = 4,
) {
    val assetsByPath: Map<String, PackAsset> get() = assets.associateBy(PackAsset::path)

    companion object {
        const val SUPPORTED_VERSION = 1
        const val PATH = "gyre-pack.json"
        const val CATALOGUE_PATH = "catalog/catalog.json"
    }
}

@Serializable
data class PackCounts(val designs: Int, val remixes: Int, val layers: Int, val assets: Int)

@Serializable
data class PackAsset(
    val path: String,
    val sha256: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
)

/**
 * What the app writes beside each import, and the marker that the import is complete.
 *
 * There is no separate index: an import exists exactly when this file is present and parses.
 * The staging directory is renamed into place whole, so the manifest cannot appear before the
 * artwork it counts, and there is no second record to fall out of step with the filesystem.
 */
@Serializable
data class ImportManifest(
    val id: String,
    val label: String,
    val kind: String,
    val importedAt: Long,
    val designs: Int,
    val remixes: Int,
    val files: Int,
    val bytes: Long,
    val formatVersion: Int = SUPPORTED_VERSION,
    /** Entries the import could not use, so the app can say so rather than quietly dropping them. */
    val skipped: Int = 0,
) {
    companion object {
        const val SUPPORTED_VERSION = 1
        const val PATH = "manifest.json"
        const val CATALOGUE_PATH = "catalog.json"
        const val KIND_PACK = "pack"
        const val KIND_IMAGES = "images"
    }
}

/** One committed import: what it is, and the pieces it contributes to the collection. */
class ImportedArtwork(val manifest: ImportManifest, val catalogue: CatalogSnapshot)

/** How far an import has got, for the one line the collection sheet gives it. */
sealed interface ImportProgress {
    data object Idle : ImportProgress

    data class Working(val label: String, val done: Int, val total: Int) : ImportProgress

    data class Finished(
        val importId: String,
        val designId: String,
        val pieces: Int,
        val skipped: Int,
    ) : ImportProgress

    data class Failed(val reason: String) : ImportProgress
}

/** Why an import stopped, phrased for the person who chose the file. */
class ImportException(message: String) : Exception(message)
