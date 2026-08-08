package dev.gyre.wallpaper.data

import android.content.Context
import android.graphics.ImageDecoder
import java.io.File
import java.io.InputStream

/**
 * Where a catalogue path's bytes actually are.
 *
 * The bundled catalogue is served from the APK's assets and imported artwork from app-private
 * storage, and the two are decoded by the same three call sites. A value type says which without
 * making every one of them ask the catalogue — and being value-equal, it doubles as the renderer's
 * texture cache key and as a `produceState` key in Compose.
 */
data class ImageSource(val kind: Kind, val path: String) {
    enum class Kind { BUNDLED, IMPORTED }

    /** Distinct across kinds, so a bundled and an imported file can never share a cache entry. */
    val cacheKey: String get() = "${kind.name}:$path"
}

fun ImageSource.openStream(context: Context): InputStream = when (kind) {
    ImageSource.Kind.BUNDLED -> context.assets.open(path)
    ImageSource.Kind.IMPORTED -> File(path).inputStream()
}

fun ImageSource.decoderSource(context: Context): ImageDecoder.Source = when (kind) {
    ImageSource.Kind.BUNDLED -> ImageDecoder.createSource(context.assets, path)
    ImageSource.Kind.IMPORTED -> ImageDecoder.createSource(File(path))
}

/**
 * Catalogue paths to the bytes behind them.
 *
 * Deliberately not a member of [CatalogRepository], where the bundled resolver used to live: which
 * store a path names is decided by its prefix alone, so this needs no catalogue state and the
 * renderer needs no catalogue to resolve — only to be handed a `Remix`.
 */
object ArtworkPaths {
    const val BUNDLED_PREFIX = "assets/"
    const val IMPORTED_PREFIX = "imported/"

    /** Everything imported lives under one directory, so one containment check covers the lot. */
    fun importsRoot(context: Context): File = File(context.filesDir, "imports")

    fun resolve(cataloguePath: String, context: Context): ImageSource =
        resolve(cataloguePath, importsRoot(context))

    fun resolve(cataloguePath: String, importsRoot: File): ImageSource {
        require(".." !in cataloguePath) { "Unsafe catalogue path: $cataloguePath" }
        return when {
            cataloguePath.startsWith(BUNDLED_PREFIX) -> ImageSource(
                ImageSource.Kind.BUNDLED,
                cataloguePath.removePrefix(BUNDLED_PREFIX),
            )

            cataloguePath.startsWith(IMPORTED_PREFIX) -> ImageSource(
                ImageSource.Kind.IMPORTED,
                importedFile(cataloguePath.removePrefix(IMPORTED_PREFIX), importsRoot).path,
            )

            // Remote layers are rejected at catalogue load, and an unprefixed path is neither store.
            else -> throw IllegalArgumentException("Catalogue path is not local: $cataloguePath")
        }
    }

    /**
     * The file [relative] names under [importsRoot], refusing anything that would leave it.
     *
     * The importer is the only writer of these paths, so the structural rules below already settle
     * it; the canonical comparison is the backstop that does not depend on that staying true. It
     * costs one stat per layer per scene switch, which is nothing beside decoding the layer.
     */
    private fun importedFile(relative: String, importsRoot: File): File {
        require(relative.isNotEmpty()) { "Imported path is empty" }
        require(!relative.startsWith("/")) { "Imported path is absolute: $relative" }
        require(relative.split('/').none(String::isEmpty)) { "Imported path is malformed: $relative" }
        val file = File(importsRoot, relative)
        val root = runCatching { importsRoot.canonicalPath }.getOrElse { importsRoot.path }
        val canonical = runCatching { file.canonicalPath }.getOrElse { file.path }
        require(canonical == root || canonical.startsWith("$root${File.separator}")) {
            "Imported path escapes the import store: $relative"
        }
        return file
    }
}
