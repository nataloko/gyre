package dev.paperouette.wallpaper.data

import android.content.Context
import dev.paperouette.wallpaper.model.Catalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Remix
import dev.paperouette.wallpaper.motion.MotionMath
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * Everything browsable at one moment, as a value.
 *
 * A snapshot rather than a set of properties on the repository, because the catalogue stopped
 * being a constant when artwork became importable: two readers — a composition and the GL thread —
 * must not be able to see the design list from one moment and the remix map from the next. Callers
 * pin one of these and work from it.
 */
class CatalogSnapshot(val designs: List<Design>, val remixes: List<Remix>) {
    private val designsById = designs.associateBy(Design::id)
    private val remixesById = remixes.associateBy(Remix::id)

    fun design(id: String): Design = requireNotNull(designsById[id]) { "Unknown design: $id" }

    fun designOrNull(id: String): Design? = designsById[id]

    fun remix(id: String): Remix = requireNotNull(remixesById[id]) { "Unknown remix: $id" }

    fun remixOrNull(id: String): Remix? = remixesById[id]

    /**
     * Deliberately `mapNotNull`: a design names its remixes by id, and once a catalogue is
     * assembled from more than one source a dangling name must shorten a list rather than throw
     * from inside a lazy grid.
     */
    fun remixesFor(designId: String): List<Remix> =
        designOrNull(designId)?.remixIds?.mapNotNull(remixesById::get).orEmpty()

    /** [other]'s pieces after this one's, which is how imports sit behind what ships. */
    operator fun plus(other: CatalogSnapshot): CatalogSnapshot =
        CatalogSnapshot(designs + other.designs, remixes + other.remixes)

    companion object {
        val Empty = CatalogSnapshot(emptyList(), emptyList())
    }
}

interface CatalogRepository {
    val current: StateFlow<CatalogSnapshot>
}

/**
 * What ships, then what the user brought.
 *
 * Bundled first, deliberately. `nextDesign` and `shuffle` index into these lists and the default
 * selection is looked up in them, so putting imports in front would move all of that under the
 * user's feet every time they added a folder. Imports arrive at the end of the collection, and
 * the app selects a new one once it lands — which is how it gets to the front of attention
 * without being put at the front of the model.
 */
class PaperouetteCatalogRepository(
    bundled: CatalogRepository,
    imported: StateFlow<List<ImportedArtwork>>,
    scope: CoroutineScope,
) : CatalogRepository {
    override val current: StateFlow<CatalogSnapshot> =
        combine(bundled.current, imported) { base, extras ->
            extras.fold(base) { catalogue, extra -> catalogue + extra.catalogue }
        }.stateIn(scope, SharingStarted.Eagerly, bundled.current.value)
}

class BundledCatalogRepository(context: Context) : CatalogRepository {
    private val snapshot = context.assets.open(CATALOGUE_PATH).bufferedReader().use { reader ->
        JSON.decodeFromString<Catalog>(reader.readText())
    }.let { catalogue ->
        val remixesById = catalogue.remixes.associateBy(Remix::id)
        val designsById = catalogue.designs.associateBy(Design::id)
        CatalogSnapshot(
            designs = catalogue.designIds.map { requireNotNull(designsById[it]) },
            remixes = catalogue.remixIds.map { requireNotNull(remixesById[it]) },
        )
    }

    override val current: StateFlow<CatalogSnapshot> = MutableStateFlow(snapshot).asStateFlow()

    // These hold the bundled catalogue alone, and must never be widened to the composed one:
    // an import is whatever the user brought, while this is what the build shipped and can be
    // counted exactly.
    init {
        val designs = snapshot.designs
        val remixes = snapshot.remixes
        check(designs.size == EXPECTED_DESIGNS) {
            "Expected $EXPECTED_DESIGNS designs, found ${designs.size}"
        }
        check(remixes.size == EXPECTED_REMIXES) {
            "Expected $EXPECTED_REMIXES remixes, found ${remixes.size}"
        }
        check(remixes.sumOf { it.layers.size } == EXPECTED_LAYERS) {
            "Expected $EXPECTED_LAYERS layer references"
        }
        check(remixes.all { remix -> remix.layers.all { !it.imageUrl.startsWith("http") } }) {
            "Runtime catalogue contains a remote layer"
        }
        // Browsing steps through a design's remixes modulo their count, so an empty design would
        // divide by zero rather than merely showing nothing.
        check(designs.all { snapshot.remixesFor(it.id).isNotEmpty() }) { "A design has no remixes" }
        check(remixes.all(::wrapsWithTheSpinPeriod)) {
            "A remix inputRotationScaler is incompatible with the spin wrap period"
        }
    }

    companion object {
        private const val CATALOGUE_PATH = "catalog/catalog.json"
        private const val EXPECTED_DESIGNS = 26
        private const val EXPECTED_REMIXES = 352
        private const val EXPECTED_LAYERS = 728
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Whether the user's own spin returns [remix] to where it started.
         *
         * The renderer multiplies spin by the remix's scaler, so the wrap period times the scaler
         * has to be a whole number of turns. Shared with the importer, which applies the same rule
         * to artwork the build never saw.
         */
        fun wrapsWithTheSpinPeriod(remix: Remix): Boolean {
            val turns = remix.inputRotationScaler * MotionMath.SPIN_WRAP_TURNS
            return abs(turns - turns.roundToInt()) < 0.0001f
        }
    }
}
