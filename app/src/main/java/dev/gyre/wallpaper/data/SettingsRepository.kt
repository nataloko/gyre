package dev.gyre.wallpaper.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.gyre.wallpaper.model.Remix
import dev.gyre.wallpaper.render.DEFAULT_ANIMATION_SPEED
import dev.gyre.wallpaper.render.MAX_ANIMATION_SPEED
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class GyreSettings(
    val automaticDarkVariants: Boolean = true,
    val tiltEnabled: Boolean = true,
    val flickEnabled: Boolean = true,
    /** Whether the artwork closes in and darkens as the launcher zooms away to its app drawer. */
    val launcherZoomEnabled: Boolean = true,
    /**
     * Holds the artwork completely still: no catalogued animation, no tilt, flick, nudge or drag,
     * and no pan or zoom from the launcher.
     *
     * It overrides the individual motion settings rather than clearing them, so turning it off
     * gives back whatever was set before. Filters are left alone — dim, greyscale and blur are
     * appearance the user dialled in deliberately, not motion.
     *
     * The point is not only stillness. With nothing moving and the animation stopped, the render
     * host asks for no frames at all, so a still wallpaper costs nothing at rest.
     */
    val stillArtwork: Boolean = false,
    val pauseOnBatterySaver: Boolean = true,
    val spinSensitivity: Float = 1f,
    val tiltSensitivity: Float = 1f,
    val flickSensitivity: Float = 1f,
    val touchInertiaSeconds: Float = 2f,
    val tapToSpin: Boolean = false,
    val dim: Float = 0f,
    val grayscale: Float = 0f,
    val blur: Float = 0f,
    /** Where the scene turns, in scene units; (0.5, 0.5) is the middle of the artwork. */
    val rotationCenterX: Float = 0.5f,
    val rotationCenterY: Float = 0.5f,
    /** Reflects the artwork left to right, which turns a spiral's arms the other way. */
    val mirrored: Boolean = false,
    /** Runs the scene's own animation backwards, leaving the artwork and your own spin alone. */
    val rotationReversed: Boolean = false,
    /** How fast the scene runs its own animation; 0 holds it still but still answers the hand. */
    val animationSpeed: Float = DEFAULT_ANIMATION_SPEED,
    /**
     * How many hours the wallpaper keeps one variant before picking another at random; 0 never.
     *
     * Measured from the last change by any means, the user's own included, so choosing a variant
     * by hand gives it the full interval rather than whatever was left of someone else's. The
     * change is applied the next time the wallpaper is looked at, so it can be late but never
     * early — see the deliberate choice in AGENTS.md for why there is no alarm.
     */
    val randomChangeHours: Int = 0,
    val favorites: Set<String> = emptySet(),
)

/** A day, past which "on its own" stops meaning anything a wallpaper can be watched for. */
const val MAX_RANDOM_CHANGE_HOURS = 24

data class ActiveSelection(
    val designId: String = DEFAULT_DESIGN,
    val remixId: String = DEFAULT_REMIX,
) {
    companion object {
        /**
         * What a fresh install opens on: the house piece, in the launcher icon's own three
         * colours. Only ever a fallback — it is read when the store has no selection saved, so
         * changing it moves the first launch and leaves everybody else where they chose to be.
         */
        const val DEFAULT_DESIGN = "gyrestack"
        const val DEFAULT_REMIX = "gyrestack_hue_house"
    }
}

internal object SettingsValidation {
    fun bounded(value: Float?, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        value?.takeIf(Float::isFinite)?.coerceIn(range) ?: default

    fun bounded(value: Int?, default: Int, range: IntRange): Int =
        value?.coerceIn(range) ?: default

    fun validIds(stored: Set<String>, valid: Set<String>): Set<String> = stored intersect valid
}

@Serializable
private data class RememberedSelections(
    val light: Map<String, String> = emptyMap(),
    val dark: Map<String, String> = emptyMap(),
)

interface SettingsRepository {
    val settings: StateFlow<GyreSettings>
    val selection: StateFlow<ActiveSelection>

    suspend fun selectRemix(remixId: String, darkMode: Boolean)
    suspend fun selectDesign(designId: String, darkMode: Boolean)
    suspend fun selectForDarkMode(darkMode: Boolean)
    suspend fun applyDarkMode(darkMode: Boolean)
    suspend fun nextRemix(darkMode: Boolean)
    suspend fun nextDesign(darkMode: Boolean)
    suspend fun shuffle(darkMode: Boolean)

    /**
     * Changes to a random variant when [GyreSettings.randomChangeHours] has elapsed since the last
     * change, and does nothing at all otherwise. Returns whether it changed anything.
     */
    suspend fun shuffleIfDue(darkMode: Boolean): Boolean
    suspend fun toggleFavorite(remixId: String)
    suspend fun update(transform: (GyreSettings) -> GyreSettings)
}

class DataStoreSettingsRepository(
    context: Context,
    private val catalogues: CatalogRepository,
    scope: CoroutineScope,
    dataStoreOverride: DataStore<Preferences>? = null,
    // Seams for the timed change, in the spirit of dataStoreOverride above: an interval measured in
    // hours cannot be tested by waiting, and a shuffle cannot be asserted on without a fixed draw.
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : SettingsRepository {
    private val dataStore = dataStoreOverride ?: PreferenceDataStoreFactory.create(
        scope = scope,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("gyre.preferences_pb") },
    )
    private val safeData = dataStore.data.catch { error ->
        if (error is IOException || error is CorruptionException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    // Both flows re-derive when the catalogue is replaced, not only when the preferences change.
    // `selection` is the load-bearing one: removing the import the wallpaper was showing changes
    // no preference at all, so without this it would go on reporting an id nothing can resolve.
    override val settings: StateFlow<GyreSettings> =
        combine(safeData, catalogues.current) { preferences, catalogue ->
            settingsFrom(preferences, catalogue)
        }.stateIn(scope, SharingStarted.Eagerly, GyreSettings())

    override val selection: StateFlow<ActiveSelection> =
        combine(safeData, catalogues.current) { preferences, catalogue ->
            val remix = activeRemix(preferences, catalogue)
            ActiveSelection(remix.designId, remix.id)
        }.stateIn(scope, SharingStarted.Eagerly, ActiveSelection())

    /**
     * [darkMode] is the theme the choice was made under, which is what the choice is filed against
     * — not the tone of the artwork chosen. Filing by tone meant a light-themed phone browsing
     * Pocket Planetarium put every pick in the dark drawer, all twelve of its remixes measuring
     * dark, and left the light drawer it would later read from empty.
     */
    override suspend fun selectRemix(remixId: String, darkMode: Boolean) {
        activate(catalogues.current.value.remix(remixId), darkMode, remember = true)
    }

    override suspend fun selectDesign(designId: String, darkMode: Boolean) {
        val catalogue = catalogues.current.value
        val remembered = rememberedRemix(safeData.first(), catalogue, designId, darkMode)
        // Only a remix the user picked is worth remembering. Recording the app's own fallback as
        // though it were a choice meant a build that resolved a design wrongly kept serving that
        // remix afterwards, even once the resolution itself was fixed.
        activate(
            remembered ?: previewVariant(catalogue, designId, darkMode),
            darkMode,
            remember = false,
        )
    }

    /**
     * Every activation stamps [Keys.LAST_CHANGED], whoever asked for it.
     *
     * The timed change measures from the artwork last changing rather than from the last *timed*
     * change, so picking a variant by hand at ten gives it the whole interval instead of whatever
     * was left of the one before it. Anything else means a wallpaper that can be chosen and then
     * replaced a minute later.
     */
    private suspend fun activate(remix: Remix, darkMode: Boolean, remember: Boolean) {
        editSafely { preferences ->
            preferences[Keys.ACTIVE_DESIGN] = remix.designId
            preferences[Keys.ACTIVE_REMIX] = remix.id
            preferences[Keys.RESOLVED_DARK] = darkMode
            preferences[Keys.LAST_CHANGED] = clock()
            if (remember) preferences.remember(remix, darkMode)
        }
    }

    /**
     * The remix paired with [remix] for [darkMode], or null when [remix] is already that side of a
     * pair, or has no pair at all.
     *
     * Pairing is by label — "Moss" and "Moss (Dark)" — and never by [Remix.isDark], which measures
     * how dark a render came out rather than which half of a pair it is. All twelve of Pocket
     * Planetarium's remixes measure dark, "Golden Hour" and "Golden Hour (Dark)" among them, so a
     * pair looked up by tone cannot be found there at all. Nor by id: the generator writes `_dark`
     * on both halves it makes itself, but the vendored designs carry the pair in the label only —
     * Spinner 32's "Blue" and "Blue (Dark)" are `_color_1` and `_color_2` — and an id rule misses
     * every one of them.
     *
     * 194 of the 352 remixes have no twin, and seven whole designs have none, so null is the
     * ordinary answer rather than the exceptional one.
     */
    private fun twinFor(catalogue: CatalogSnapshot, remix: Remix, darkMode: Boolean): Remix? {
        if (remix.label.endsWith(DARK_LABEL_SUFFIX) == darkMode) return null
        val twin = if (darkMode) {
            remix.label + DARK_LABEL_SUFFIX
        } else {
            remix.label.removeSuffix(DARK_LABEL_SUFFIX)
        }
        // Excluding the remix itself: Spinner 18 carries two remixes both labelled "Blue".
        return catalogue.remixesFor(remix.designId)
            .firstOrNull { it.id != remix.id && it.label == twin }
    }

    /**
     * The remix shown on a design's own tile, swapped for its dark or light twin when the
     * catalogue actually pairs the two.
     *
     * Anchored on the artwork of the tile the user tapped. Treating "first dark remix" as the
     * design's dark form handed back artwork the user never picked — tapping Rainbow Whirlpool
     * produced Dot Bomb — so only a label-paired twin is taken, and a design without one keeps
     * its own preview.
     */
    private fun previewVariant(
        catalogue: CatalogSnapshot,
        designId: String,
        darkMode: Boolean,
    ): Remix {
        val preview = catalogue.designOrNull(designId)
            ?.let { catalogue.remixOrNull(it.previewRemixId) }
            ?.takeIf { it.designId == designId }
            ?: catalogue.remixesFor(designId).first()
        return twinFor(catalogue, preview, darkMode) ?: preview
    }

    // Selection mutations read the store directly instead of the StateFlow caches: on a cold
    // start the caches still hold defaults until the first disk read lands, and acting on those
    // defaults could overwrite the user's saved selection.
    /**
     * Follows the system theme, and only a change of it.
     *
     * This used to re-resolve on every call, and its callers are every launch and every time the
     * wallpaper comes back into view — so a variant picked by hand lasted until the next visit to
     * the home screen. The theme the selection was resolved against is now recorded beside it, and
     * nothing happens until the system disagrees with that record.
     *
     * The stamp is kept up to date even while the setting is off, so that switching it on later
     * measures from the theme in force rather than from whatever it was last resolved for.
     */
    override suspend fun selectForDarkMode(darkMode: Boolean) {
        val preferences = safeData.first()
        val resolvedFor = preferences[Keys.RESOLVED_DARK]
            // No record and no selection is a first run, which has nothing to lose by being
            // resolved now. No record but a selection is an upgrade, and it keeps what it has.
            ?: if (preferences[Keys.ACTIVE_REMIX] == null) !darkMode else darkMode
        if (resolvedFor == darkMode) return
        val catalogue = catalogues.current.value
        if (!settingsFrom(preferences, catalogue).automaticDarkVariants) {
            editSafely { it[Keys.RESOLVED_DARK] = darkMode }
            return
        }
        resolve(preferences, catalogue, darkMode)
    }

    /** Applies the theme to what is showing now, for when "Match the system theme" is switched on. */
    override suspend fun applyDarkMode(darkMode: Boolean) {
        resolve(safeData.first(), catalogues.current.value, darkMode)
    }

    /**
     * The user's own pick for the theme being moved to, else the twin of what is *showing*, else
     * what is showing.
     *
     * Never the design's preview. 194 of the 352 variants have no twin to move to and seven whole
     * designs have none at all, so handing those the artwork on the design's tile would throw the
     * user's choice away and give nothing back for it. Anchoring on what is showing rather than on
     * the preview also keeps a second pair straight: from "Slate" the dark form is "Slate (Dark)",
     * not the preview's "Moss (Dark)".
     */
    private suspend fun resolve(
        preferences: Preferences,
        catalogue: CatalogSnapshot,
        darkMode: Boolean,
    ) {
        val current = activeRemix(preferences, catalogue)
        val replacement = rememberedRemix(preferences, catalogue, current.designId, darkMode)
            ?: twinFor(catalogue, current, darkMode)
        if (replacement == null) {
            editSafely { it[Keys.RESOLVED_DARK] = darkMode }
        } else {
            // remember = false: only remixes the user actually picked belong in that map.
            activate(replacement, darkMode, remember = false)
        }
    }

    // Browsing covers every remix of the design. Restricting it to remixes matching the system
    // theme both hid most of a design's artwork and, from a light remix, jumped straight to an
    // unrelated dark effect.
    override suspend fun nextRemix(darkMode: Boolean) {
        val catalogue = catalogues.current.value
        val current = activeRemix(safeData.first(), catalogue)
        val candidates = catalogue.remixesFor(current.designId)
        val index = candidates.indexOfFirst { it.id == current.id }
        selectRemix(candidates[(index + 1).mod(candidates.size)].id, darkMode)
    }

    override suspend fun nextDesign(darkMode: Boolean) {
        val catalogue = catalogues.current.value
        val designId = activeRemix(safeData.first(), catalogue).designId
        val currentIndex = catalogue.designs.indexOfFirst { it.id == designId }
        val next = catalogue.designs[(currentIndex + 1).mod(catalogue.designs.size)]
        selectDesign(next.id, darkMode)
    }

    /** A variant from anywhere in the collection, other than the one showing. */
    private fun randomRemix(
        preferences: Preferences,
        catalogue: CatalogSnapshot,
        darkMode: Boolean,
    ): Remix {
        val candidates = catalogue.remixes.let { remixes ->
            if (settingsFrom(preferences, catalogue).automaticDarkVariants) {
                remixes.filter { it.isDark == darkMode }.ifEmpty { remixes }
            } else {
                remixes
            }
        }
        val current = activeRemix(preferences, catalogue).id
        return candidates.filterNot { it.id == current }.randomOrNull(random)
            ?: candidates.first()
    }

    override suspend fun shuffle(darkMode: Boolean) {
        val preferences = safeData.first()
        val catalogue = catalogues.current.value
        selectRemix(randomRemix(preferences, catalogue, darkMode).id, darkMode)
    }

    /**
     * The timed change, checked on the hook that already runs [selectForDarkMode] — every time the
     * wallpaper comes into view — rather than driven by an alarm.
     *
     * An exact alarm would need a manifest receiver, and `tools/package_release.py` asserts the
     * release APK declares none; see the deliberate choice in AGENTS.md. The cost is that the
     * change arrives the next time the home screen comes forward, so it is late rather than exact.
     *
     * `remember = false`, because only variants the user actually picked belong in the
     * remembered-variants map. Filing the app's own choice there is what let a wrong resolution
     * outlive the fix that corrected it, and this one is not even a resolution — it is a draw.
     */
    override suspend fun shuffleIfDue(darkMode: Boolean): Boolean {
        val preferences = safeData.first()
        val catalogue = catalogues.current.value
        val hours = settingsFrom(preferences, catalogue).randomChangeHours
        if (hours <= 0) return false
        val now = clock()
        val last = preferences[Keys.LAST_CHANGED]
        // No stamp is an upgrade or a first run, and a stamp in the future is a clock that has
        // been put back. Both start the interval from now rather than spending it all at once.
        if (last == null || now < last) {
            editSafely { it[Keys.LAST_CHANGED] = now }
            return false
        }
        if (now - last < hours * MILLIS_PER_HOUR) return false
        activate(randomRemix(preferences, catalogue, darkMode), darkMode, remember = false)
        return true
    }

    override suspend fun toggleFavorite(remixId: String) {
        catalogues.current.value.remix(remixId)
        editSafely { preferences ->
            val favorites = preferences[Keys.FAVORITES].orEmpty()
            preferences[Keys.FAVORITES] = if (remixId in favorites) {
                favorites - remixId
            } else {
                favorites + remixId
            }
        }
    }

    override suspend fun update(transform: (GyreSettings) -> GyreSettings) {
        val catalogue = catalogues.current.value
        editSafely { preferences ->
            val current = settingsFrom(preferences, catalogue)
            val updated = transform(current)
            preferences[Keys.AUTOMATIC_DARK] = updated.automaticDarkVariants
            preferences[Keys.TILT] = updated.tiltEnabled
            preferences[Keys.FLICK] = updated.flickEnabled
            preferences[Keys.LAUNCHER_ZOOM] = updated.launcherZoomEnabled
            preferences[Keys.STILL_ARTWORK] = updated.stillArtwork
            preferences[Keys.PAUSE_BATTERY] = updated.pauseOnBatterySaver
            preferences[Keys.SPIN_SENSITIVITY] = updated.spinSensitivity.coerceIn(0.25f, 2f)
            preferences[Keys.TILT_SENSITIVITY] = updated.tiltSensitivity.coerceIn(0.25f, 2f)
            preferences[Keys.FLICK_SENSITIVITY] = updated.flickSensitivity.coerceIn(0.25f, 2f)
            preferences[Keys.INERTIA] = updated.touchInertiaSeconds.coerceIn(0f, 4f)
            preferences[Keys.TAP_SPIN] = updated.tapToSpin
            preferences[Keys.DIM] = updated.dim.coerceIn(0f, 1f)
            preferences[Keys.GRAYSCALE] = updated.grayscale.coerceIn(0f, 1f)
            preferences[Keys.BLUR] = updated.blur.coerceIn(0f, 1f)
            preferences[Keys.ROTATION_CENTER_X] = updated.rotationCenterX.coerceIn(0f, 1f)
            preferences[Keys.ROTATION_CENTER_Y] = updated.rotationCenterY.coerceIn(0f, 1f)
            preferences[Keys.MIRRORED] = updated.mirrored
            preferences[Keys.ROTATION_REVERSED] = updated.rotationReversed
            preferences[Keys.ANIMATION_SPEED] =
                updated.animationSpeed.coerceIn(0f, MAX_ANIMATION_SPEED)
            val hours = updated.randomChangeHours.coerceIn(0, MAX_RANDOM_CHANGE_HOURS)
            preferences[Keys.RANDOM_CHANGE_HOURS] = hours
            // A new interval runs from now. Without this it would be measured against whenever the
            // artwork last changed, so turning on "every 24 hours" beside a week-old selection
            // would spend the whole interval the moment the panel was closed.
            if (hours != current.randomChangeHours) preferences[Keys.LAST_CHANGED] = clock()
            preferences[Keys.FAVORITES] = updated.favorites
        }
    }

    private fun activeRemix(preferences: Preferences, catalogue: CatalogSnapshot): Remix {
        val remixId = preferences[Keys.ACTIVE_REMIX] ?: ActiveSelection.DEFAULT_REMIX
        return catalogue.remixOrNull(remixId) ?: defaultRemix(catalogue)
    }

    private fun remembered(preferences: Preferences, designId: String, darkMode: Boolean): String? {
        val selections = rememberedSelections(preferences) ?: return null
        return (if (darkMode) selections.dark else selections.light)[designId]
    }

    /** The remix the user last picked for [designId] under [darkMode], if it still resolves. */
    private fun rememberedRemix(
        preferences: Preferences,
        catalogue: CatalogSnapshot,
        designId: String,
        darkMode: Boolean,
    ): Remix? = remembered(preferences, designId, darkMode)
        ?.let(catalogue::remixOrNull)
        ?.takeIf { it.designId == designId }

    /**
     * Variants the user chose, or null when the stored map predates [REMEMBERED_VERSION].
     *
     * Earlier builds wrote automatic selections into this map, so their entries cannot be told
     * apart from real choices and are discarded on upgrade rather than trusted.
     */
    private fun rememberedSelections(preferences: Preferences): RememberedSelections? {
        if (preferences[Keys.REMEMBERED_VERSION] != REMEMBERED_VERSION) return null
        return preferences[Keys.REMEMBERED]?.let { encoded ->
            runCatching { JSON.decodeFromString<RememberedSelections>(encoded) }.getOrNull()
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.remember(
        remix: Remix,
        darkMode: Boolean,
    ) {
        val selections = rememberedSelections(this) ?: RememberedSelections()
        val updated = if (darkMode) {
            selections.copy(dark = selections.dark + (remix.designId to remix.id))
        } else {
            selections.copy(light = selections.light + (remix.designId to remix.id))
        }
        this[Keys.REMEMBERED_VERSION] = REMEMBERED_VERSION
        this[Keys.REMEMBERED] = JSON.encodeToString(RememberedSelections.serializer(), updated)
    }

    private fun settingsFrom(preferences: Preferences, catalogue: CatalogSnapshot) = GyreSettings(
        automaticDarkVariants = preferences[Keys.AUTOMATIC_DARK] ?: true,
        tiltEnabled = preferences[Keys.TILT] ?: true,
        flickEnabled = preferences[Keys.FLICK] ?: true,
        launcherZoomEnabled = preferences[Keys.LAUNCHER_ZOOM] ?: true,
        stillArtwork = preferences[Keys.STILL_ARTWORK] ?: false,
        pauseOnBatterySaver = preferences[Keys.PAUSE_BATTERY] ?: true,
        spinSensitivity = SettingsValidation.bounded(
            preferences[Keys.SPIN_SENSITIVITY],
            1f,
            0.25f..2f,
        ),
        tiltSensitivity = SettingsValidation.bounded(
            preferences[Keys.TILT_SENSITIVITY],
            1f,
            0.25f..2f,
        ),
        flickSensitivity = SettingsValidation.bounded(
            preferences[Keys.FLICK_SENSITIVITY],
            1f,
            0.25f..2f,
        ),
        touchInertiaSeconds = SettingsValidation.bounded(
            preferences[Keys.INERTIA],
            2f,
            0f..4f,
        ),
        tapToSpin = preferences[Keys.TAP_SPIN] ?: false,
        dim = SettingsValidation.bounded(preferences[Keys.DIM], 0f, 0f..1f),
        grayscale = SettingsValidation.bounded(preferences[Keys.GRAYSCALE], 0f, 0f..1f),
        blur = SettingsValidation.bounded(preferences[Keys.BLUR], 0f, 0f..1f),
        rotationCenterX = SettingsValidation.bounded(
            preferences[Keys.ROTATION_CENTER_X],
            0.5f,
            0f..1f,
        ),
        rotationCenterY = SettingsValidation.bounded(
            preferences[Keys.ROTATION_CENTER_Y],
            0.5f,
            0f..1f,
        ),
        mirrored = preferences[Keys.MIRRORED] ?: false,
        rotationReversed = preferences[Keys.ROTATION_REVERSED] ?: false,
        animationSpeed = SettingsValidation.bounded(
            preferences[Keys.ANIMATION_SPEED],
            DEFAULT_ANIMATION_SPEED,
            0f..MAX_ANIMATION_SPEED,
        ),
        randomChangeHours = SettingsValidation.bounded(
            preferences[Keys.RANDOM_CHANGE_HOURS],
            0,
            0..MAX_RANDOM_CHANGE_HOURS,
        ),
        // Recomputed from the catalogue in force rather than snapshotted once at construction:
        // an import adds ids afterwards, and a favourite kept on an imported variant would
        // otherwise be filtered straight back out on every read.
        favorites = SettingsValidation.validIds(
            preferences[Keys.FAVORITES].orEmpty(),
            catalogue.remixes.mapTo(mutableSetOf(), Remix::id),
        ),
    )

    private suspend fun editSafely(
        transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        try {
            dataStore.edit { preferences -> transform(preferences) }
        } catch (_: IOException) {
            // Keep the last validated in-memory state when storage is temporarily unavailable.
        } catch (_: CorruptionException) {
            // The configured corruption handler restores defaults on the next read.
        }
    }

    private fun defaultRemix(catalogue: CatalogSnapshot): Remix =
        catalogue.remixOrNull(ActiveSelection.DEFAULT_REMIX) ?: catalogue.remixes.first()

    private object Keys {
        val ACTIVE_DESIGN = stringPreferencesKey("active_design")
        val ACTIVE_REMIX = stringPreferencesKey("active_remix")
        val REMEMBERED = stringPreferencesKey("remembered_variants")

        /**
         * The system theme the active selection was last resolved against.
         *
         * Absent means the selection predates the record, which counts as already resolved: an
         * upgrade must not treat the pick it inherits as stale and swap it out.
         */
        val RESOLVED_DARK = booleanPreferencesKey("selection_resolved_dark")
        val REMEMBERED_VERSION = intPreferencesKey("remembered_variants_version")
        val AUTOMATIC_DARK = booleanPreferencesKey("automatic_dark")
        val TILT = booleanPreferencesKey("tilt")
        val FLICK = booleanPreferencesKey("flick")
        val LAUNCHER_ZOOM = booleanPreferencesKey("launcher_zoom")
        val STILL_ARTWORK = booleanPreferencesKey("still_artwork")
        val PAUSE_BATTERY = booleanPreferencesKey("pause_battery")
        val SPIN_SENSITIVITY = floatPreferencesKey("spin_sensitivity")
        val TILT_SENSITIVITY = floatPreferencesKey("tilt_sensitivity")
        val FLICK_SENSITIVITY = floatPreferencesKey("flick_sensitivity")
        val INERTIA = floatPreferencesKey("touch_inertia")
        val TAP_SPIN = booleanPreferencesKey("tap_spin")
        val DIM = floatPreferencesKey("dim")
        val GRAYSCALE = floatPreferencesKey("grayscale")
        val BLUR = floatPreferencesKey("blur")
        val ROTATION_CENTER_X = floatPreferencesKey("rotation_center_x")
        val ROTATION_CENTER_Y = floatPreferencesKey("rotation_center_y")
        val MIRRORED = booleanPreferencesKey("mirrored")
        val ROTATION_REVERSED = booleanPreferencesKey("rotation_reversed")
        val ANIMATION_SPEED = floatPreferencesKey("animation_speed")
        val RANDOM_CHANGE_HOURS = intPreferencesKey("random_change_hours")

        /**
         * When the artwork last changed, by any means.
         *
         * The only wall-clock time this app keeps outside an import's manifest. Absent means an
         * upgrade or a fresh install, which starts the interval rather than firing it.
         */
        val LAST_CHANGED = longPreferencesKey("selection_changed_at")
        val FAVORITES = stringSetPreferencesKey("favorites")
    }

    private companion object {
        const val DARK_LABEL_SUFFIX = " (Dark)"
        const val MILLIS_PER_HOUR = 3_600_000L

        /**
         * Bumped when stored variant memories can no longer be trusted.
         *
         * Four was a catalogue rebuild that changed every identifier, leaving the map holding
         * names for pieces that no longer existed.
         *
         * Five, because the drawers changed meaning: they hold what was picked under a theme
         * rather than what measured dark. The two disagree systematically, not occasionally — a
         * light-themed phone browsing Pocket Planetarium filed all twelve of its picks as dark —
         * so reading v4 entries as v5 would claim picks the user never made under that theme, and
         * hand them back on the first flip. Only the per-design memory resets; the wallpaper on
         * screen is held separately and is untouched.
         */
        const val REMEMBERED_VERSION = 5
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
