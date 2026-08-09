package dev.paperouette.wallpaper.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun everyManifestIdIsResolvedInsideTheImportRoot() {
        val store = ImportStore(temporary.root)
        listOf("../sibling", "/tmp/sibling", "", "ABCDEF0123456789").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { store.directoryFor(id) }
            assertThrows(IllegalArgumentException::class.java) { store.contains(id) }
            assertThrows(IllegalArgumentException::class.java) { store.remove(id) }
        }
    }

    @Test
    fun anEscapingRemovalCannotTouchASiblingDirectory() {
        val sibling = File(temporary.root.parentFile, "import-store-sibling-${System.nanoTime()}")
        sibling.mkdirs()
        val marker = File(sibling, "keep").apply { writeText("kept") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ImportStore(temporary.root).remove("../${sibling.name}")
            }
            assertEquals("kept", marker.readText())
        } finally {
            sibling.deleteRecursively()
        }
    }
}
