package dev.gyre.wallpaper.data

import dev.gyre.wallpaper.model.Catalog
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Where imported artwork lives, and the rules that keep a half-finished import invisible.
 *
 * ```
 * filesDir/imports/<importId>/manifest.json      the commit marker
 * filesDir/imports/<importId>/catalog.json       already namespaced
 * filesDir/imports/<importId>/artwork/<sha256>.webp
 * filesDir/imports.staging/<uuid>/               in progress; read by nothing
 * ```
 *
 * There is deliberately no index file. An import exists exactly when its directory holds a
 * manifest that parses, and the whole directory is renamed into place in one step — so there is
 * no second record that can disagree with the filesystem, and a crash at any point leaves debris
 * to sweep rather than a dangling reference to chase.
 *
 * Each import owns its artwork rather than sharing one content-addressed pool. The same photo in
 * two imports is then stored twice, which is the price of a delete that cannot reach into a
 * sibling — and the alternative, reference counting or mark-and-sweep across imports, is a way
 * to lose someone's wallpaper to an off-by-one.
 */
class ImportStore(private val filesDir: File) {
    private val root = File(filesDir, "imports")
    private val staging = File(filesDir, "imports.staging")

    /**
     * Clears anything a previous run left half-done.
     *
     * Runs before the first read, so an interrupted import cannot show up as a design whose
     * artwork is missing.
     */
    fun sweep() {
        staging.deleteRecursively()
        root.listFiles()?.forEach { directory ->
            if (directory.isDirectory && readManifest(directory) == null) {
                directory.deleteRecursively()
            }
        }
    }

    /** Every committed import, oldest first, so the collection grows at its end. */
    fun list(): List<ImportedArtwork> = root.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .mapNotNull(::read)
        .sortedBy { it.manifest.importedAt }

    fun contains(importId: String): Boolean = readManifest(File(root, importId)) != null

    fun directoryFor(importId: String): File = File(root, importId)

    /**
     * Runs [block] against a fresh staging directory, deleting it however [block] ends.
     *
     * Commit is [block]'s job: it moves the directory into place with [commit], and anything it
     * leaves behind is debris by definition.
     */
    fun <T> staged(block: (File) -> T): T {
        val directory = File(staging, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create a staging directory" }
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Publishes [staged] as [importId], atomically.
     *
     * A rename within one filesystem either happens or does not, so the manifest inside can never
     * be seen before the artwork it counts.
     */
    fun commit(staged: File, importId: String) {
        root.mkdirs()
        val destination = File(root, importId)
        destination.deleteRecursively()
        check(staged.renameTo(destination)) { "Cannot publish the import" }
    }

    /**
     * Removes an import, manifest first.
     *
     * Renaming out of `imports/` is what makes it gone: the deletion that follows can take as long
     * as it likes, and a crash midway leaves debris under staging rather than a design pointing at
     * artwork that is half deleted.
     */
    fun remove(importId: String) {
        val directory = File(root, importId)
        if (!directory.isDirectory) return
        staging.mkdirs()
        val discarded = File(staging, "removed-${UUID.randomUUID()}")
        if (directory.renameTo(discarded)) discarded.deleteRecursively() else directory.deleteRecursively()
    }

    fun writeManifest(directory: File, manifest: ImportManifest) {
        File(directory, ImportManifest.PATH)
            .writeText(JSON.encodeToString(ImportManifest.serializer(), manifest))
    }

    fun writeCatalogue(directory: File, catalogue: Catalog) {
        File(directory, ImportManifest.CATALOGUE_PATH)
            .writeText(JSON.encodeToString(Catalog.serializer(), catalogue))
    }

    private fun read(directory: File): ImportedArtwork? {
        val manifest = readManifest(directory) ?: return null
        val catalogue = runCatching {
            JSON.decodeFromString<Catalog>(
                File(directory, ImportManifest.CATALOGUE_PATH).readText(),
            )
        }.getOrNull() ?: return null
        val byId = catalogue.remixes.associateBy { it.id }
        val designsById = catalogue.designs.associateBy { it.id }
        return ImportedArtwork(
            manifest = manifest,
            catalogue = CatalogSnapshot(
                designs = catalogue.designIds.mapNotNull(designsById::get),
                remixes = catalogue.remixIds.mapNotNull(byId::get),
            ),
        )
    }

    private fun readManifest(directory: File): ImportManifest? = runCatching {
        JSON.decodeFromString<ImportManifest>(File(directory, ImportManifest.PATH).readText())
    }.getOrNull()?.takeIf { it.formatVersion == ImportManifest.SUPPORTED_VERSION }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
