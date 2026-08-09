package dev.paperouette.wallpaper.data

import android.graphics.BitmapFactory
import dev.paperouette.wallpaper.model.Catalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Remix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Brings artwork in from the phone's own storage.
 *
 * It lives on the application rather than behind a ViewModel: a 180 MiB copy has to outlive the
 * sheet being dismissed and the user going home, and there is no ViewModel layer here to give it
 * that. One import runs at a time, and the staging directory is cleared however it ends.
 *
 * Two kinds arrive through the same door. A **pack** carries its own catalogue and is copied
 * verbatim against declared checksums; anything else is taken as **pictures** and given a
 * catalogue here. The user is never asked which they have, because a zip of holiday photos and a
 * zip of catalogued artwork are the same gesture and only one of them is a thing anybody knows the
 * name of.
 */
class ArtworkImporter(
    private val store: ImportStore,
    private val scope: CoroutineScope,
    private val usableSpace: () -> Long,
) {
    private val running = Mutex()
    private val _imported = MutableStateFlow<List<ImportedArtwork>>(emptyList())
    private val _progress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)

    /** Every committed import, oldest first. */
    val imported: StateFlow<List<ImportedArtwork>> = _imported.asStateFlow()
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    @Volatile
    private var job: Job? = null
    @Volatile
    private var activeSource: ImportSource? = null

    init {
        // Before anything reads it: an interrupted import must not surface as a design whose
        // artwork was never written.
        store.sweep()
        _imported.value = store.list()
    }

    /** Starts an import if the source was accepted immediately rather than queued. */
    fun start(source: ImportSource): Boolean {
        if (!running.tryLock()) {
            source.close()
            return false
        }
        activeSource = source
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val finished = withContext(Dispatchers.IO) { source.use(::import) }
                _imported.value = store.list()
                _progress.value = finished
            } catch (_: CancellationException) {
                // Cancel is an explicit user action. The source and staging area close in their
                // respective finally blocks, and Idle was already published by cancel().
            } catch (error: ImportException) {
                currentCoroutineContext().ensureActive()
                _progress.value = ImportProgress.Failed(error.message.orEmpty())
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _progress.value = ImportProgress.Failed(
                    error.message?.takeIf(String::isNotBlank) ?: "The import could not be read",
                )
            } finally {
                activeSource = null
                job = null
                running.unlock()
            }
        }
        job = launched
        if (!launched.start()) {
            activeSource = null
            job = null
            source.close()
            running.unlock()
            return false
        }
        return true
    }

    fun cancel() {
        activeSource?.close()
        job?.cancel()
        job = null
        _progress.value = ImportProgress.Idle
    }

    /** Clears a finished or failed state once the sheet has shown it. */
    fun acknowledge() {
        if (_progress.value !is ImportProgress.Working) _progress.value = ImportProgress.Idle
    }

    suspend fun remove(importId: String) {
        withContext(Dispatchers.IO) { store.remove(importId) }
        _imported.value = store.list()
    }

    /** Reads whatever was picked, deciding what it is from its first entry. */
    private fun import(source: ImportSource): ImportProgress.Finished = store.staged { staging ->
        var collector: Collector? = null
        var seen = 0
        source.forEachEntry { entry ->
            ensureRunning()
            seen++
            if (seen > MAX_ENTRIES) throw ImportException("That archive holds too many files")
            val current = collector ?: newCollector(entry, source.label).also { collector = it }
            current.accept(entry, staging)
        }
        val finished = collector ?: throw ImportException("There was nothing in that to import")
        finished.finish(staging)
    }

    private fun newCollector(first: ImportSource.Entry, label: String): Collector =
        if (first.name == PackManifest.PATH) {
            PackCollector(label)
        } else {
            ImageCollector(label)
        }

    /** One pass over the entries, then whatever it has gathered written into the staging area. */
    private interface Collector {
        fun accept(entry: ImportSource.Entry, staging: () -> File)

        fun finish(staging: () -> File): ImportProgress.Finished
    }

    /**
     * A pack, read in the order the exporter wrote it.
     *
     * The manifest first, so free space and the entry list are known before a byte is written;
     * then the catalogue; then the artwork, each verified against its declared digest as it
     * streams. Anything the manifest does not declare is ignored — which is also what makes a
     * traversal impossible, since no entry name ever reaches the filesystem.
     */
    private inner class PackCollector(private val sourceLabel: String) : Collector {
        private var manifest: PackManifest? = null
        private var catalogue: Catalog? = null
        private val stored = mutableSetOf<String>()
        private var bytes = 0L

        override fun accept(entry: ImportSource.Entry, staging: () -> File) {
            val pack = manifest
            when {
                pack == null -> manifest = acceptManifest(entry)
                catalogue == null -> catalogue = acceptCatalogue(entry, pack)
                else -> {
                    val declared = pack.assetsByPath[entry.name] ?: return
                    val name = declared.path.removePrefix(ARTWORK_PREFIX)
                    if (!stored.add(name)) {
                        throw ImportException("That pack contains the same file twice")
                    }
                    val artwork = File(staging(), "artwork").apply { mkdirs() }
                    val destination = File(artwork, name)
                    bytes = Math.addExact(bytes, copyVerified(entry.stream, destination, declared))
                    verifyDimensions(destination, declared)
                    report(pack.name, stored.size, pack.counts.assets)
                }
            }
        }

        override fun finish(staging: () -> File): ImportProgress.Finished {
            // The collector only exists because the first entry was the manifest, and reading
            // it either succeeded or threw, so this cannot be null by the time finish runs.
            val pack = requireNotNull(manifest) { "Pack collector finished without a manifest" }
            val parsed = catalogue ?: throw ImportException("That pack has no catalogue")
            if (stored.size != pack.counts.assets) {
                throw ImportException(
                    "That pack is incomplete — ${stored.size} of ${pack.counts.assets} files",
                )
            }
            if (bytes != pack.totalBytes) {
                throw ImportException("That pack's copied size disagrees with its manifest")
            }
            val sizes = pack.assets.associate {
                it.path.removePrefix(ARTWORK_PREFIX) to (it.width to it.height)
            }
            val result = ImportedCatalog.namespaced(
                importId = pack.packId,
                catalogue = parsed,
                available = stored,
                sizeOf = { path -> ImportedCatalog.fileNameOf(path)?.let(sizes::get) },
            )
            if (result.snapshot.designs.isEmpty()) {
                throw ImportException("Nothing in that pack could be used")
            }
            return commit(
                staging = staging(),
                snapshot = result.snapshot,
                manifest = ImportManifest(
                    id = pack.packId,
                    label = pack.name.ifBlank { sourceLabel },
                    kind = ImportManifest.KIND_PACK,
                    importedAt = System.currentTimeMillis(),
                    designs = result.snapshot.designs.size,
                    remixes = result.snapshot.remixes.size,
                    files = stored.size,
                    bytes = bytes,
                    skipped = result.skipped,
                ),
            )
        }
    }

    /**
     * Pictures, each normalised to artwork the renderer can treat like any other.
     *
     * The whole import's identity is the digests of what it holds, so the same folder picked twice
     * is recognised rather than copied again — which cannot be known until the last file is read,
     * so that check happens at the end here rather than at the beginning as it does for a pack.
     */
    private inner class ImageCollector(private val sourceLabel: String) : Collector {
        private val images = mutableListOf<SynthesisedCatalog.Image>()
        private var skipped = 0
        private var bytes = 0L

        override fun accept(entry: ImportSource.Entry, staging: () -> File) {
            if (images.size >= MAX_IMAGES) {
                skipped++
                return
            }
            if (usableSpace() < HEADROOM_BYTES) {
                throw ImportException("Not enough space to import any more")
            }
            val raw = entry.stream.readBoundedBytes(
                MAX_IMAGE_BYTES,
                "One of those pictures is too large to import",
            )
            ensureRunning()
            val normalised = ImageNormaliser.normalise(raw)
            if (normalised == null) {
                // Not an image, or one this device cannot decode. Counted and carried on from:
                // a stray text file in a photo folder should not lose the other two hundred.
                skipped++
                return
            }
            ensureRunning()
            val artwork = File(staging(), "artwork").apply { mkdirs() }
            val masterFile = write(artwork, normalised.master)
            val thumbFile = write(artwork, normalised.thumb)
            bytes += normalised.master.size + normalised.thumb.size
            images += SynthesisedCatalog.Image(
                fileName = entry.name,
                masterFile = masterFile,
                thumbFile = thumbFile,
                palette = normalised.palette,
                isDark = normalised.isDark,
            )
            report(sourceLabel, images.size, null)
        }

        override fun finish(staging: () -> File): ImportProgress.Finished {
            if (images.isEmpty()) {
                throw ImportException(
                    if (skipped > 0) "None of those files could be read as pictures"
                    else "There were no pictures in that",
                )
            }
            val importId = identity(images.map { it.masterFile })
            if (store.contains(importId)) {
                throw ImportException("Those pictures are already imported")
            }
            val label = SynthesisedCatalog.labelFor(sourceLabel)
            val snapshot = SynthesisedCatalog.forImages(importId, label, images)
            return commit(
                staging = staging(),
                snapshot = snapshot,
                manifest = ImportManifest(
                    id = importId,
                    label = label,
                    kind = ImportManifest.KIND_IMAGES,
                    importedAt = System.currentTimeMillis(),
                    designs = 1,
                    remixes = snapshot.remixes.size,
                    files = images.size * 2,
                    bytes = bytes,
                    skipped = skipped,
                ),
            )
        }

        /** Content-addressed, so the same picture twice in one folder costs one file. */
        private fun write(artwork: File, data: ByteArray): String {
            val name = "${MessageDigest.getInstance("SHA-256").digest(data).toHex()}.webp"
            val file = File(artwork, name)
            if (!file.exists()) file.writeBytes(data)
            return name
    }
}

    /** Writes the catalogue and manifest, then publishes the staging directory. */
    private fun commit(
        staging: File,
        snapshot: CatalogSnapshot,
        manifest: ImportManifest,
    ): ImportProgress.Finished {
        store.writeCatalogue(staging, snapshot.asCatalog())
        store.writeManifest(staging, manifest)
        store.commit(staging, manifest.id)
        return ImportProgress.Finished(
            importId = manifest.id,
            designId = snapshot.designs.first().id,
            pieces = snapshot.designs.size,
            skipped = manifest.skipped,
        )
    }

    /** The first entry, which must be the manifest and must be affordable. */
    private fun acceptManifest(entry: ImportSource.Entry): PackManifest {
        val manifest = readManifest(entry.stream)
        ImportedPackValidator.validateManifest(manifest)
        if (store.contains(manifest.packId)) {
            throw ImportException("${manifest.name} is already imported")
        }
        val available = usableSpace()
        if (available < HEADROOM_BYTES || manifest.totalBytes > available - HEADROOM_BYTES) {
            throw ImportException(
                "Not enough space — this needs ${manifest.totalBytes / 1024 / 1024} MB",
            )
        }
        return manifest
    }

    private fun acceptCatalogue(entry: ImportSource.Entry, manifest: PackManifest): Catalog {
        if (entry.name != PackManifest.CATALOGUE_PATH) {
            throw ImportException("That pack has no catalogue")
        }
        val bytes = entry.stream.readBoundedBytes(
            MAX_CATALOGUE_BYTES,
            "That pack's catalogue is too large to read",
        )
        val catalogue = readCatalogue(bytes)
        ImportedPackValidator.validateCatalogue(catalogue, manifest)
        ImportedPackValidator.requireIdentity(manifest, bytes)
        return catalogue
    }

    private fun readManifest(stream: InputStream): PackManifest {
        val bytes = stream.readBoundedBytes(
            MAX_MANIFEST_BYTES,
            "That pack's manifest is too large to read",
        )
        val manifest = runCatching {
            JSON.decodeFromString<PackManifest>(bytes.decodeToString())
        }.getOrElse { throw ImportException("That pack's manifest could not be read") }
        return manifest
    }

    private fun readCatalogue(bytes: ByteArray): Catalog = runCatching {
        JSON.decodeFromString<Catalog>(bytes.decodeToString())
        }.getOrElse { throw ImportException("That pack's catalogue could not be read") }

    /**
     * Copies one declared asset, refusing it the moment it stops matching what was promised.
     *
     * The size is checked while copying rather than afterwards, so a zip that claims 600 KB and
     * delivers a gigabyte is stopped at the limit instead of after it.
     */
    private fun copyVerified(stream: InputStream, destination: File, declared: PackAsset): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        destination.outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                ensureRunning()
                val read = stream.read(buffer)
                if (read <= 0) break
                copied += read
                if (copied > declared.bytes) {
                    throw ImportException("${declared.path} is larger than the manifest declares")
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (copied != declared.bytes) throw ImportException("${declared.path} is truncated")
        if (digest.digest().toHex() != declared.sha256) {
            throw ImportException("${declared.path} does not match the pack's checksum")
        }
        return copied
    }

    private fun verifyDimensions(file: File, declared: PackAsset) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth != declared.width || bounds.outHeight != declared.height) {
            throw ImportException("${declared.path} does not match its declared dimensions")
        }
    }

    private fun InputStream.readBoundedBytes(limit: Int, tooLarge: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, initialBufferSize(this)))
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0
        while (true) {
            ensureRunning()
            val read = read(buffer)
            if (read <= 0) break
            if (read > limit - total) throw ImportException(tooLarge)
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun initialBufferSize(stream: InputStream): Int =
        runCatching { stream.available().coerceIn(32, COPY_BUFFER_BYTES) }.getOrDefault(32)

    private fun ensureRunning() {
        if (job?.isActive != true) throw CancellationException("Import cancelled")
    }

    /** An import's identity, derived from what it holds so the same input is recognised again. */
    private fun identity(fileNames: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fileNames.sorted().forEach { digest.update(it.encodeToByteArray()) }
        return digest.digest().toHex().take(IMPORT_ID_LENGTH)
    }

    private fun report(label: String, done: Int, total: Int?) {
        // At most one emission every REPORT_EVERY files: a 516-file pack would otherwise push
        // five hundred states through a flow that draws one line of text.
        if (done == 1 || done % REPORT_EVERY == 0 || done == total) {
            _progress.value = ImportProgress.Working(label, done, total)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ARTWORK_PREFIX = "artwork/"
        const val MAX_ENTRIES = 5_000
        const val MAX_IMAGES = 200
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val MAX_CATALOGUE_BYTES = 8 * 1024 * 1024
        const val HEADROOM_BYTES = 32L * 1024 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val REPORT_EVERY = 8
        const val IMPORT_ID_LENGTH = 16
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The snapshot back as the serialisable shape the store writes. */
internal fun CatalogSnapshot.asCatalog(): Catalog = Catalog(
    designIds = designs.map(Design::id),
    remixIds = remixes.map(Remix::id),
    designs = designs,
    remixes = remixes,
)
