package dev.paperouette.wallpaper.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.paperouette.wallpaper.ui.theme.PaperouetteMotion
import kotlin.math.roundToInt

/**
 * The controls the whole interface is built from.
 *
 * Deliberately not Material's own: these sit translucently on top of moving artwork rather than on
 * a page, so they are drawn flat, carry their own contrast, and share one geometry — a 6 dp track,
 * a 20 dp knob, a fully round pill. Each carries the semantics its Material counterpart would, so
 * a screen reader and the instrumentation suite still see a slider, a switch or a button.
 */

/** Everything round in the interface is fully round; nothing is half-rounded. */
val PillShape = RoundedCornerShape(percent = 50)

private val TRACK_HEIGHT = 6.dp
private val KNOB_SIZE = 20.dp
private val KNOB_RING = 3.dp
private val FADER_HEIGHT = 44.dp

/**
 * A labelled value, dragged along a track.
 *
 * Commits on release rather than per pixel: each commit is a DataStore write and a renderer update,
 * and one drag across the track would otherwise queue a few hundred of them.
 */
@Composable
fun PaperouetteFader(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    onValue: (Float) -> Unit,
) {
    var live by remember(value) { mutableFloatStateOf(value) }

    fun snap(raw: Float): Float {
        val bounded = raw.coerceIn(range.start, range.endInclusive)
        if (steps <= 0) return bounded
        val interval = (range.endInclusive - range.start) / (steps + 1)
        return range.start + ((bounded - range.start) / interval).roundToInt() * interval
    }

    // The knob's centre travels between its own half-widths, so the value the user reads off a
    // position matches the value the same position sets.
    fun valueAt(x: Float, width: Float, knob: Float): Float {
        val travel = (width - knob).coerceAtLeast(1f)
        val fraction = ((x - knob / 2f) / travel).coerceIn(0f, 1f)
        return snap(range.start + fraction * (range.endInclusive - range.start))
    }

    // The caller's modifier lands on the track rather than on the whole block: the track is the
    // control, so that is where a test tag and the slider semantics have to agree.
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                valueLabel(live),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val active = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.outlineVariant
        val ring = MaterialTheme.colorScheme.surface
        val fraction = ((live - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(FADER_HEIGHT)
                .semantics {
                    contentDescription = title
                    progressBarRangeInfo = ProgressBarRangeInfo(live, range, steps)
                    setProgress { target ->
                        live = snap(target)
                        onValue(live)
                        true
                    }
                }
                .pointerInput(range, steps) {
                    detectTapGestures { position ->
                        live = valueAt(position.x, size.width.toFloat(), KNOB_SIZE.toPx())
                        onValue(live)
                    }
                }
                .pointerInput(range, steps) {
                    detectHorizontalDragGestures(
                        onDragStart = { position ->
                            live = valueAt(position.x, size.width.toFloat(), KNOB_SIZE.toPx())
                        },
                        onDragEnd = { onValue(live) },
                        onDragCancel = { onValue(live) },
                    ) { change, _ ->
                        change.consume()
                        live = valueAt(change.position.x, size.width.toFloat(), KNOB_SIZE.toPx())
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val middle = size.height / 2f
                val thickness = TRACK_HEIGHT.toPx()
                val knob = KNOB_SIZE.toPx()
                val centre = knob / 2f + fraction * (size.width - knob).coerceAtLeast(0f)
                drawLine(
                    color = inactive,
                    start = Offset(thickness / 2f, middle),
                    end = Offset(size.width - thickness / 2f, middle),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = active,
                    start = Offset(thickness / 2f, middle),
                    end = Offset(centre.coerceAtLeast(thickness / 2f), middle),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
                drawCircle(ring, knob / 2f, Offset(centre, middle))
                drawCircle(active, knob / 2f - KNOB_RING.toPx(), Offset(centre, middle))
            }
        }
    }
}

/** A title, a line of explanation and a switch, the whole row being the target. */
@Composable
fun PaperouetteToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        PaperouetteSwitch(checked)
    }
}

/** Purely the picture of a switch; [PaperouetteToggleRow] owns the interaction and the semantics. */
@Composable
private fun PaperouetteSwitch(checked: Boolean) {
    val travel by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = PaperouetteMotion.fastSpatial(),
        label = "switch",
    )
    val track by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = PaperouetteMotion.effects(),
        label = "switchTrack",
    )
    Box(
        Modifier
            .size(width = SWITCH_WIDTH, height = SWITCH_HEIGHT)
            .clip(PillShape)
            .background(track)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PillShape)
            .padding(SWITCH_INSET),
        contentAlignment = Alignment.CenterStart,
    ) {
        val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (SWITCH_WIDTH - SWITCH_INSET * 2 - SWITCH_KNOB).toPx()
        }
        Box(
            Modifier
                .offset { IntOffset((travel * travelPx).roundToInt(), 0) }
                .size(SWITCH_KNOB)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
        )
    }
}

private val SWITCH_WIDTH = 46.dp
private val SWITCH_HEIGHT = 28.dp
private val SWITCH_INSET = 4.dp
private val SWITCH_KNOB = 20.dp

/** The one filled action on a surface. */
@Composable
fun PaperouettePrimaryAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

/** Secondary actions: outlined, so they never compete with the filled one beside them. */
@Composable
fun PaperouetteGhostAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(PillShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** A round icon-only action, for the things whose picture says it better than a word would. */
@Composable
fun PaperouetteIconAction(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            Modifier.size(22.dp),
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A filter: filled when it is on, outlined when it is not. */
@Composable
fun PaperouetteChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onSelected: (Boolean) -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = PaperouetteMotion.effects(),
        label = "chip",
    )
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(PillShape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PillShape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelected)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = content)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * A heading inside a panel: small, widely tracked and quiet, so it separates the sections without
 * competing with the artwork behind them.
 *
 * [top] is the air the heading opens above itself, and is a parameter because it is a measure of
 * the gap rather than of the label: in a list that spaces its own children the container has
 * already paid part of it, and the default would land the heading further out than every other
 * one in the app.
 */
@Composable
fun PaperouetteSectionLabel(text: String, modifier: Modifier = Modifier, top: Dp = 22.dp) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = top, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp, fontSize = 11.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One line of running text inside a panel. */
@Composable
fun PaperouetteBodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
