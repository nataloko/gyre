package dev.gyre.wallpaper.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one place a catalogue path becomes a file, and so the one place a bad path can be caught.
 *
 * The importer writes every path this resolves, but that is a property of today's code rather than
 * of the format, and the store holds whatever the user last imported.
 */
class ArtworkPathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val importsRoot: File get() = temporaryFolder.root

    @Test
    fun aBundledPathLosesItsPrefixAndKeepsTheRest() {
        val source = ArtworkPaths.resolve("assets/artwork/abc.webp", importsRoot)

        assertEquals(ImageSource(ImageSource.Kind.BUNDLED, "artwork/abc.webp"), source)
    }

    @Test
    fun anImportedPathResolvesUnderTheImportStore() {
        val source = ArtworkPaths.resolve("imported/pack01/artwork/abc.webp", importsRoot)

        assertEquals(ImageSource.Kind.IMPORTED, source.kind)
        assertEquals(File(importsRoot, "pack01/artwork/abc.webp").path, source.path)
    }

    /** Two stores can hold the same relative path, and the renderer caches by this key. */
    @Test
    fun theCacheKeyDistinguishesTheTwoStores() {
        val bundled = ArtworkPaths.resolve("assets/artwork/abc.webp", importsRoot)
        val imported = ArtworkPaths.resolve("imported/artwork/abc.webp", importsRoot)

        assertEquals(false, bundled.cacheKey == imported.cacheKey)
    }

    @Test
    fun traversalIsRefusedInEitherStore() {
        listOf(
            "assets/../../etc/passwd",
            "imported/../../etc/passwd",
            "imported/pack01/../../../etc/passwd",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                ArtworkPaths.resolve(path, importsRoot)
            }
        }
    }

    @Test
    fun anAbsoluteOrMalformedImportedPathIsRefused() {
        listOf("imported//artwork/abc.webp", "imported/", "imported/pack01//abc.webp").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                ArtworkPaths.resolve(it, importsRoot)
            }
        }
    }

    /** A remote URL is rejected at catalogue load; an unprefixed path belongs to neither store. */
    @Test
    fun aPathBelongingToNoStoreIsRefused() {
        listOf("artwork/abc.webp", "https://example.invalid/a.webp", "").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                ArtworkPaths.resolve(path, importsRoot)
            }
        }
    }
}
