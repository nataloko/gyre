package dev.gyre.wallpaper.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Everything the importer needs from a zip or a folder, and nothing about how it was chosen.
 *
 * The picker's `Uri` stops here. Instrumentation cannot mint a content Uri without a
 * `ContentProvider`, and adding one would break the release packaging check, so the pipeline is
 * written against this instead and the Uri plumbing is confined to the two implementations.
 *
 * Entries arrive in one pass and each stream is valid only inside its visit — a zip read from a
 * content Uri cannot be rewound. Implementations must present the manifest and the catalogue
 * before any artwork, which a zip gets from the exporter's entry order and a folder arranges for
 * itself.
 */
interface ImportSource : Closeable {
    /** What the user will see this import called: a filename or a folder name. */
    val label: String

    fun forEachEntry(visit: (Entry) -> Unit)

    class Entry(val name: String, val size: Long, val stream: InputStream)
}

/**
 * A zip, streamed rather than opened.
 *
 * `ZipFile` would be easier to read from, but a content Uri has no file to seek in and staging
 * 180 MiB to reach the central directory would double the disk the import needs.
 */
class ZipImportSource(
    override val label: String,
    private val onClose: () -> Unit = {},
    private val open: () -> InputStream,
) : ImportSource {
    private var stream: ZipInputStream? = null

    override fun forEachEntry(visit: (ImportSource.Entry) -> Unit) {
        val zip = ZipInputStream(open().buffered(BUFFER_BYTES)).also { stream = it }
        // Shields the entry stream from being closed by whatever reads it: closing a
        // ZipInputStream ends the whole archive, not the entry.
        val entryStream = object : FilterInputStream(zip) {
            override fun close() = Unit
        }
        while (true) {
            // The platform refuses a traversing entry name itself, before the name is even
            // returned. Nothing here relies on that — the importer writes only what the manifest
            // declared — but the message it throws is for a developer, not for whoever picked the
            // file, and an archive built to escape its own directory is not one to read the rest
            // of.
            val entry = try {
                zip.nextEntry ?: break
            } catch (error: ZipException) {
                throw ImportException("That archive is not safe to open: ${error.message}")
            }
            if (!entry.isDirectory) {
                visit(ImportSource.Entry(entry.name, entry.size, entryStream))
            }
            zip.closeEntry()
        }
    }

    override fun close() {
        stream?.close()
        stream = null
        // Where the picker's read grant is given back. The import outlives the activity that
        // chose the file, so the grant has to be persisted to survive the copy — and released
        // once it is done, rather than left standing over the user's storage.
        onClose()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * A folder the user picked, walked with the framework's own `DocumentsContract`.
 *
 * Not `androidx.documentfile`: it would be a new dependency, and under strict dependency
 * verification that means regenerating the checksum metadata for what amounts to thirty lines of
 * cursor reading.
 *
 * Entries come out in name order, and the walk is bounded in both depth and count so that a folder
 * pointed at the root of someone's storage cannot run until the battery gives out.
 */
class DocumentTreeImportSource(
    private val resolver: ContentResolver,
    private val tree: Uri,
    override val label: String,
    private val onClose: () -> Unit = {},
) : ImportSource {
    override fun forEachEntry(visit: (ImportSource.Entry) -> Unit) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        walk(root, depth = 0, seen = Counter(), visit = visit)
    }

    override fun close() = onClose()

    private class Counter(var files: Int = 0)

    private fun walk(
        documentId: String,
        depth: Int,
        seen: Counter,
        visit: (ImportSource.Entry) -> Unit,
    ) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val folders = mutableListOf<String>()
        val files = mutableListOf<Pair<String, Long>>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += id
                } else {
                    files += id to cursor.getLong(3)
                }
            }
        }
        files.sortedBy { it.first }.forEach { (id, size) ->
            if (seen.files >= MAX_FILES) return
            seen.files++
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val name = displayName(document) ?: id
            resolver.openInputStream(document)?.use { stream ->
                visit(ImportSource.Entry(name, size, stream))
            }
        }
        folders.forEach { walk(it, depth + 1, seen, visit) }
    }

    private fun displayName(document: Uri): String? = resolver.query(
        document,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_FILES = 2_000
    }
}
