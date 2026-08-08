package dev.gyre.wallpaper.data

import dev.gyre.wallpaper.model.Catalog
import dev.gyre.wallpaper.model.Design
import dev.gyre.wallpaper.model.Layer
import dev.gyre.wallpaper.model.Remix

/**
 * Turns a pack's catalogue into one the app can add to its own.
 *
 * Two jobs, both done once at import time rather than on every read, so what lands on disk is
 * already what the app will serve and the ids stored in DataStore never shift under a rewrite.
 *
 * **Renaming.** Every id gains an `import:<importId>:` prefix. This is not a precaution: the
 * bundled catalogue kept all fourteen `spinner_*` design ids and all 208 of their remix ids from
 * the artwork these packs are made of, so an unprefixed import would collide on every one of
 * them. A colon appears in no bundled id, which makes the collision impossible rather than
 * merely unlikely — `ImportedCatalogTest` holds that.
 *
 * **Refusing.** A pack is a file people hand around, so what it claims is checked rather than
 * trusted. A remix that would render wrongly is dropped or defanged instead of being allowed to
 * break the stage, and the caller is told how many.
 */
object ImportedCatalog {
    const val ID_PREFIX = "import:"

    /** Content-addressed artwork lives under the import's own directory. */
    fun artworkPath(importId: String, fileName: String): String =
        "${ArtworkPaths.IMPORTED_PREFIX}$importId/artwork/$fileName"

    fun idFor(importId: String, sourceId: String): String = "$ID_PREFIX$importId:$sourceId"

    /** The import an id belongs to, or null when it is bundled. */
    fun importIdOf(id: String): String? =
        id.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.substringBefore(':')

    class Result(val snapshot: CatalogSnapshot, val skipped: Int)

    /**
     * [catalogue] renamed into [importId]'s namespace, minus whatever cannot be drawn.
     *
     * [sizeOf] gives a layer's pixel dimensions by its *original* catalogue path — the pack
     * manifest already carries them, so nothing is decoded to answer it. A layer it does not know
     * is treated as unusable rather than assumed square.
     */
    fun namespaced(
        importId: String,
        catalogue: Catalog,
        available: Set<String>,
        sizeOf: (String) -> Pair<Int, Int>?,
    ): Result {
        var skipped = 0
        val remixes = catalogue.remixes.mapNotNull { remix ->
            val rewritten = rewrite(importId, remix, available, sizeOf)
            if (rewritten == null) skipped++
            rewritten
        }
        val byId = remixes.associateBy(Remix::id)

        val designs = catalogue.designs.mapNotNull { design ->
            val own = design.remixIds.map { idFor(importId, it) }.filter(byId::containsKey)
            if (own.isEmpty()) {
                return@mapNotNull null
            }
            val preview = idFor(importId, design.previewRemixId).takeIf(byId::containsKey)
            Design(
                id = idFor(importId, design.id),
                label = design.label,
                previewRemixId = preview ?: own.first(),
                remixIds = own,
            )
        }

        // Ordered by the catalogue's own lists where it gives them, so a pack browses the way its
        // author arranged it rather than the way the JSON happened to serialise.
        val designsById = designs.associateBy(Design::id)
        val ordered = catalogue.designIds.mapNotNull { designsById[idFor(importId, it)] }
            .ifEmpty { designs }
        val orderedRemixes = ordered.flatMap { design -> design.remixIds.mapNotNull(byId::get) }
        return Result(CatalogSnapshot(ordered, orderedRemixes), skipped)
    }

    private fun rewrite(
        importId: String,
        remix: Remix,
        available: Set<String>,
        sizeOf: (String) -> Pair<Int, Int>?,
    ): Remix? {
        if (remix.layers.isEmpty()) return null
        if (!BundledCatalogRepository.wrapsWithTheSpinPeriod(remix)) return null
        val paths = remix.layers.map(Layer::imageUrl) + remix.previews.thumb
        if (paths.any { fileNameOf(it) == null || fileNameOf(it) !in available }) return null

        val baseSize = sizeOf(remix.layers.first().imageUrl) ?: return null
        val layers = remix.layers.mapIndexed { index, layer ->
            layer.copy(
                imageUrl = artworkPath(importId, requireNotNull(fileNameOf(layer.imageUrl))),
                rotation = if (index == 0 && baseSize.first != baseSize.second) {
                    // The shader turns the scene in normalised coordinates, so rotating a
                    // non-square base layer shears the artwork rather than turning it, and no
                    // framing rule can undo that. Held still, it is cover-fitted and correct.
                    null
                } else {
                    layer.rotation
                },
                parallaxScale = if (index == 0 && layer.rotation == null) {
                    // A static base layer is framed by cover-fit with no headroom reserved, so
                    // panning it would slide the window past the edge of the artwork.
                    0f
                } else {
                    layer.parallaxScale
                },
            )
        }
        return remix.copy(
            id = idFor(importId, remix.id),
            designId = idFor(importId, remix.designId),
            previews = remix.previews.copy(
                thumb = artworkPath(importId, requireNotNull(fileNameOf(remix.previews.thumb))),
            ),
            layers = layers,
        )
    }

    /**
     * The content-addressed file a catalogue path names, or null if it names anything else.
     *
     * The only thing that ever reaches the filesystem, which is what makes a path like
     * `../../../etc/passwd` in a hand-edited pack an ignored entry rather than a traversal.
     */
    fun fileNameOf(cataloguePath: String): String? =
        cataloguePath.removePrefix(SOURCE_PREFIX)
            .takeIf { cataloguePath.startsWith(SOURCE_PREFIX) && CONTENT_ADDRESSED.matches(it) }

    private const val SOURCE_PREFIX = "assets/artwork/"
    private val CONTENT_ADDRESSED = Regex("[0-9a-f]{64}\\.(webp|png|jpg|jpeg)")
}
