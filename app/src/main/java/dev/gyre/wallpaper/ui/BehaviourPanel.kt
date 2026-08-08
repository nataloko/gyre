package dev.gyre.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gyre.wallpaper.BuildConfig
import dev.gyre.wallpaper.data.GyreSettings
import dev.gyre.wallpaper.data.MAX_RANDOM_CHANGE_HOURS
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How the artwork answers the phone: the sensors it listens to, how hard it reacts, and what it
 * does on a battery saver.
 *
 * Everything here is a preference rather than something you would set while watching, which is why
 * it is a panel of its own instead of living over the stage the way [LookPanel] does. The stage
 * stops animating while this is open, since none of it is visible behind.
 */
@Composable
fun BehaviourPanel(
    settings: GyreSettings,
    darkMode: Boolean,
    onUpdate: ((GyreSettings) -> GyreSettings) -> Unit,
    onAutomaticDarkChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            // Opaque, not glass: the sheet is still composed underneath, and even a few percent of
            // it showing through puts "Set wallpaper" behind these rows.
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Tagged here rather than on the box around it, so a test that scrolls this panel is
        // addressing the thing that actually scrolls.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("behaviour_panel"),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item("header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Behaviour",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    GyreIconAction(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close",
                        modifier = Modifier.testTag("close_behaviour"),
                        onClick = onClose,
                    )
                }
            }

            item("motion_label") { GyreSectionLabel("Motion") }
            item("still") {
                GyreToggleRow(
                    title = "Hold still",
                    summary = "A plain picture: nothing animates, leans, spins or pans",
                    checked = settings.stillArtwork,
                ) { value -> onUpdate { it.copy(stillArtwork = value) } }
            }
            item("tilt") {
                GyreToggleRow(
                    title = "Tilt",
                    summary = "The artwork leans as the phone does",
                    checked = settings.tiltEnabled,
                ) { value -> onUpdate { it.copy(tiltEnabled = value) } }
            }
            item("flick") {
                GyreToggleRow(
                    title = "Flick",
                    summary = "A quick turn of the phone sets it spinning",
                    checked = settings.flickEnabled,
                ) { value -> onUpdate { it.copy(flickEnabled = value) } }
            }
            item("nudge") {
                GyreToggleRow(
                    title = "Nudge",
                    summary = "A single tap adds a spin",
                    checked = settings.tapToSpin,
                ) { value -> onUpdate { it.copy(tapToSpin = value) } }
            }

            item("response_label") { GyreSectionLabel("How hard it reacts") }
            item("touch_strength") {
                StrengthFader("Touch", settings.spinSensitivity, "fader_touch") { value ->
                    onUpdate { it.copy(spinSensitivity = value) }
                }
            }
            item("tilt_strength") {
                StrengthFader("Tilt", settings.tiltSensitivity, "fader_tilt") { value ->
                    onUpdate { it.copy(tiltSensitivity = value) }
                }
            }
            item("flick_strength") {
                StrengthFader("Flick", settings.flickSensitivity, "fader_flick") { value ->
                    onUpdate { it.copy(flickSensitivity = value) }
                }
            }
            item("drift") {
                GyreFader(
                    title = "Drift",
                    value = settings.touchInertiaSeconds,
                    range = 0f..4f,
                    steps = 7,
                    valueLabel = { value -> String.format(Locale.getDefault(), "%.1f s", value) },
                    modifier = Modifier.testTag("fader_drift"),
                ) { value -> onUpdate { it.copy(touchInertiaSeconds = value) } }
            }

            item("home_label") { GyreSectionLabel("Home screen") }
            item("launcher_zoom") {
                GyreToggleRow(
                    title = "Give way to the app drawer",
                    summary = "The artwork closes in and dims as the drawer opens",
                    checked = settings.launcherZoomEnabled,
                ) { value -> onUpdate { it.copy(launcherZoomEnabled = value) } }
            }

            item("artwork_label") { GyreSectionLabel("Artwork") }
            item("automatic_dark") {
                GyreToggleRow(
                    title = "Match the system theme",
                    summary = if (darkMode) {
                        "Choosing dark variants, as the system is dark"
                    } else {
                        "Choosing light variants, as the system is light"
                    },
                    checked = settings.automaticDarkVariants,
                    onChecked = onAutomaticDarkChanged,
                )
            }
            item("random_change") {
                GyreFader(
                    title = "Change on its own",
                    value = settings.randomChangeHours.toFloat(),
                    range = 0f..MAX_RANDOM_CHANGE_HOURS.toFloat(),
                    steps = MAX_RANDOM_CHANGE_HOURS - 1,
                    valueLabel = { value ->
                        when (val hours = value.roundToInt()) {
                            0 -> "Never"
                            1 -> "1 hour"
                            else -> "$hours hours"
                        }
                    },
                    modifier = Modifier.testTag("fader_random_change"),
                ) { value -> onUpdate { it.copy(randomChangeHours = value.roundToInt()) } }
            }
            item("random_change_note") {
                GyreBodyText(
                    "A piece and variant picked at random, arriving the next time the home " +
                        "screen comes up after the time is out.",
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }

            item("power_label") { GyreSectionLabel("Power") }
            item("battery") {
                GyreToggleRow(
                    title = "Hold still on battery saver",
                    summary = "Show a single frame while the phone is saving power",
                    checked = settings.pauseOnBatterySaver,
                ) { value -> onUpdate { it.copy(pauseOnBatterySaver = value) } }
            }

            item("gestures_label") { GyreSectionLabel("Gestures") }
            item("gestures") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GyreBodyText("Drag — pan the artwork, which drifts on after you let go")
                    GyreBodyText("Two fingers — the next variant of this piece")
                    GyreBodyText("Three fingers — the next piece")
                    Spacer(Modifier.height(2.dp))
                    GyreBodyText("These work on the stage and on the home screen alike.")
                }
            }

            item("version") { VersionLine() }
        }
    }
}

/**
 * Which build this is, at the very foot of the panel.
 *
 * Quiet, and the last thing here, because it answers a question only asked when something is
 * already wrong — the app is installed over adb rather than from a store, so "which build is on
 * this phone" has no other way of being answered. A debug build says so itself, through the
 * version name's own suffix.
 */
@Composable
private fun VersionLine() {
    Text(
        "Gyre ${BuildConfig.VERSION_NAME}",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp)
            .testTag("version"),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The three response controls share a scale, so they share a shape. */
@Composable
private fun StrengthFader(title: String, value: Float, tag: String, onValue: (Float) -> Unit) {
    GyreFader(
        title = title,
        value = value,
        range = 0.25f..2f,
        steps = 6,
        valueLabel = { current -> String.format(Locale.getDefault(), "%.2f×", current) },
        modifier = Modifier.testTag(tag),
        onValue = onValue,
    )
}
