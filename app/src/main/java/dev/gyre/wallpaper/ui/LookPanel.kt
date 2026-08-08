package dev.gyre.wallpaper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gyre.wallpaper.data.GyreSettings
import dev.gyre.wallpaper.model.Remix
import dev.gyre.wallpaper.render.MAX_ANIMATION_SPEED
import dev.gyre.wallpaper.render.RotationCenter
import dev.gyre.wallpaper.render.SceneCoverage
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The bundled artwork is square. */
private const val SCENE_ASPECT = 1f

private val PANEL_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Room the panel always leaves above itself, for the status bar and the Filters/Centre row. */
private val PANEL_HEADROOM = 96.dp

private enum class LookTab(val label: String) {
    FILTERS("Filters"),
    CENTRE("Centre"),
}

/**
 * Dim, greyscale, blur and the centre of rotation, set while looking at the artwork they change.
 *
 * The whole point of doing this here rather than on a settings page is scale: the stage behind this
 * panel is the size the wallpaper will actually be, so 40% blur looks like 40% blur, and the centre
 * of rotation is placed by putting a finger where it should go instead of on a scale model of the
 * screen.
 *
 * The two jobs are separated because they want the same space. Placing the centre needs the whole
 * surface to be reachable, which it cannot be while three faders are sitting over the bottom of it.
 */
@Composable
fun LookPanel(
    artwork: Remix,
    settings: GyreSettings,
    onUpdate: ((GyreSettings) -> GyreSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tabName by rememberSaveable { mutableStateOf(LookTab.FILTERS.name) }
    val tab = LookTab.valueOf(tabName)
    val centre = RotationCenter(settings.rotationCenterX, settings.rotationCenterY)
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }

    BoxWithConstraints(modifier.fillMaxSize().testTag("look_panel")) {
        val stageAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f

        if (tab == LookTab.CENTRE) {
            // Takes the touches; draws nothing. The crosshair is composed after the panel below, so
            // a centre placed low is still visible rather than being swallowed by it.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("centre_stage")
                    .pointerInput(artwork.id) {
                        fun report(position: Offset) = onUpdate {
                            it.copy(
                                rotationCenterX = (position.x / size.width).coerceIn(0f, 1f),
                                rotationCenterY = (position.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                        detectTapGestures(onTap = ::report)
                    }
                    .pointerInput(artwork.id) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            onUpdate {
                                it.copy(
                                    rotationCenterX = (change.position.x / size.width).coerceIn(0f, 1f),
                                    rotationCenterY = (change.position.y / size.height).coerceIn(0f, 1f),
                                )
                            }
                        }
                    },
            )
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LookTab.entries.forEach { entry ->
                GyreChip(
                    label = entry.label,
                    selected = tab == entry,
                    modifier = Modifier.testTag("look_tab_${entry.name.lowercase()}"),
                    onSelected = { tabName = entry.name },
                )
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            GyreIconAction(
                icon = Icons.Outlined.Close,
                contentDescription = "Close",
                modifier = Modifier.testTag("close_look"),
                onClick = onClose,
            )
        }

        // As tall as its own controls and no taller: the sheet is taken out from behind it while
        // this panel is open, so it no longer has to cover the sheet's footprint. That is what
        // keeps the bottom of the stage — and a centre placed near it — in view.
        //
        // Still opaque rather than glass, because the sheet is composed underneath even when it is
        // parked off screen.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Bounded and scrollable, because a short window has less room than the Filters tab
                // wants: on a landscape phone its controls are taller than the screen, and without
                // this the panel grew up over the tab row and took it with it.
                .heightIn(max = maxHeight - PANEL_HEADROOM)
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                .background(MaterialTheme.colorScheme.surface, PANEL_SHAPE)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            when (tab) {
                LookTab.FILTERS -> FilterControls(settings, onUpdate)
                LookTab.CENTRE -> CentreControls(artwork, centre, stageAspect, onUpdate)
            }
        }

        if (tab == LookTab.CENTRE) {
            // Above the panel, so the last strip of the stage does not hide the very thing being
            // placed. Neither of these takes pointer input, so a drag still reaches the stage
            // underneath and the panel's own controls stay tappable through them.
            Crosshair(centre)
            CentreReadout(centre, barHeight + ARROW_EDGE_GAP + ARROW_SIZE + READOUT_GAP_TO_ARROW)
            // Held off until the panel has been measured, so they do not start at the screen's
            // edge and jump up on the next frame.
            if (barHeight > 0.dp) {
                CentreArrows(centre, barHeight) { moved ->
                    onUpdate { it.copy(rotationCenterX = moved.x, rotationCenterY = moved.y) }
                }
            }
        }
    }
}

@Composable
private fun FilterControls(
    settings: GyreSettings,
    onUpdate: ((GyreSettings) -> GyreSettings) -> Unit,
) {
    fun percent(value: Float) = "${(value * 100).roundToInt()}%"

    GyreFader(
        title = "Dim",
        value = settings.dim,
        range = 0f..1f,
        steps = 9,
        valueLabel = ::percent,
        modifier = Modifier.testTag("fader_dim"),
    ) { value -> onUpdate { it.copy(dim = value) } }
    GyreFader(
        title = "Greyscale",
        value = settings.grayscale,
        range = 0f..1f,
        steps = 9,
        valueLabel = ::percent,
        modifier = Modifier.testTag("fader_greyscale"),
    ) { value -> onUpdate { it.copy(grayscale = value) } }
    GyreFader(
        title = "Blur",
        value = settings.blur,
        range = 0f..1f,
        steps = 9,
        valueLabel = ::percent,
        modifier = Modifier.testTag("fader_blur"),
    ) { value -> onUpdate { it.copy(blur = value) } }
    Spacer(Modifier.height(4.dp))
    // How fast the scene runs its own animation, which is a third thing the same pair of toggles
    // below govern the shape of — so it belongs here, judged against the moving stage, rather than
    // over in Behaviour where Drift sets how long *your* push coasts for.
    GyreFader(
        title = "Speed",
        value = settings.animationSpeed,
        range = 0f..MAX_ANIMATION_SPEED,
        steps = 11,
        valueLabel = { value ->
            if (value <= 0f) "Still" else String.format(Locale.getDefault(), "%.2f×", value)
        },
        modifier = Modifier.testTag("fader_speed"),
    ) { value -> onUpdate { it.copy(animationSpeed = value) } }
    Spacer(Modifier.height(4.dp))
    // A pair: one flips the picture, the other flips the turn. Between them they cover all four
    // ways round a spiral can run, which is why they sit together rather than one of them living
    // over in Behaviour with the sensors.
    GyreToggleRow(
        title = "Mirror",
        summary = "Flip the artwork left to right, so its arms curl the other way",
        checked = settings.mirrored,
        modifier = Modifier.testTag("mirror_toggle"),
    ) { value -> onUpdate { it.copy(mirrored = value) } }
    GyreToggleRow(
        title = "Reverse",
        summary = "Turn the other way, leaving the artwork as it is",
        checked = settings.rotationReversed,
        modifier = Modifier.testTag("reverse_toggle"),
    ) { value -> onUpdate { it.copy(rotationReversed = value) } }
    if (settings.dim > 0f || settings.grayscale > 0f || settings.blur > 0f) {
        Spacer(Modifier.height(8.dp))
        GyreGhostAction(
            label = "Clear",
            icon = Icons.Outlined.FilterBAndW,
            modifier = Modifier.testTag("clear_filters"),
        ) { onUpdate { it.copy(dim = 0f, grayscale = 0f, blur = 0f) } }
    }
}

/**
 * Moving the centre out is not free: the corner diagonally opposite is then further away and sweeps
 * a wider circle, so the scene crops harder to keep the artwork's edges out of view. The readout
 * states how much survives, so the cost shows while placing it rather than on the home screen.
 */
@Composable
private fun CentreControls(
    artwork: Remix,
    centre: RotationCenter,
    stageAspect: Float,
    onUpdate: ((GyreSettings) -> GyreSettings) -> Unit,
) {
    val visibleFraction = remember(artwork.id, centre, stageAspect) {
        val window = SceneCoverage.window(artwork, SCENE_ASPECT, stageAspect, centre.sanitized())
        val full = SceneCoverage.window(artwork, SCENE_ASPECT, stageAspect, RotationCenter.Center)
        if (full.y <= 0f) 1f else (window.x * window.y) / (full.x * full.y)
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Touch the artwork, or nudge with the arrows",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // The numbers are what makes "exactly there" checkable, and repeatable another day.
            GyreBodyText(
                "${(centre.x * 100).roundToInt()}% across · " +
                    "${(centre.y * 100).roundToInt()}% down",
            )
            GyreBodyText("Shows ${(visibleFraction * 100).roundToInt()}% of the artwork")
        }
        if (centre != RotationCenter.Center) {
            GyreGhostAction(
                label = "Middle",
                icon = Icons.Outlined.Restore,
                modifier = Modifier.testTag("reset_rotation_center"),
            ) { onUpdate { it.copy(rotationCenterX = 0.5f, rotationCenterY = 0.5f) } }
        }
    }
}

/**
 * Four arrows at the edges of the stage, for the last few pixels.
 *
 * Touching the artwork places the centre roughly, and roughly is all a fingertip can do — it covers
 * the very thing being aimed at. These move it a hundredth of the surface at a time, which is a few
 * dp, so a point can be put exactly where it is wanted. Holding one repeats.
 *
 * They sit at the edges rather than beside the crosshair so they never end up under the point being
 * placed, and the two that would collide with the tab row and the panel are held clear of both.
 *
 * The horizontal pair is placed absolutely rather than by start and end. `rotationCenterX` is a
 * fraction measured from the left of the surface whichever way the language runs, so under a
 * right-to-left layout the direction-aware alignment put "move the centre left" against the right
 * edge while it still moved the point left.
 *
 * For the same reason these keep the plain arrow icons over the deprecated-in-favour-of
 * `Icons.AutoMirrored` ones: an auto-mirrored glyph turns round under a right-to-left layout, and
 * this arrow means the physical direction the centre travels, not the direction the text reads.
 */
@Suppress("DEPRECATION")
@Composable
private fun BoxWithConstraintsScope.CentreArrows(
    centre: RotationCenter,
    barHeight: Dp,
    onCentre: (RotationCenter) -> Unit,
) {
    CentreArrow(
        icon = Icons.Outlined.KeyboardArrowLeft,
        description = "Move the centre left",
        stepX = -1f,
        stepY = 0f,
        centre = centre,
        onCentre = onCentre,
        modifier = Modifier
            .align(AbsoluteAlignment.CenterLeft)
            .absolutePadding(left = ARROW_EDGE_GAP),
    )
    CentreArrow(
        icon = Icons.Outlined.KeyboardArrowRight,
        description = "Move the centre right",
        stepX = 1f,
        stepY = 0f,
        centre = centre,
        onCentre = onCentre,
        modifier = Modifier
            .align(AbsoluteAlignment.CenterRight)
            .absolutePadding(right = ARROW_EDGE_GAP),
    )
    CentreArrow(
        icon = Icons.Outlined.KeyboardArrowUp,
        description = "Move the centre up",
        stepX = 0f,
        stepY = -1f,
        centre = centre,
        onCentre = onCentre,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = ARROW_CLEAR_OF_TABS),
    )
    CentreArrow(
        icon = Icons.Outlined.KeyboardArrowDown,
        description = "Move the centre down",
        stepX = 0f,
        stepY = 1f,
        centre = centre,
        onCentre = onCentre,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = barHeight + ARROW_EDGE_GAP),
    )
}

@Composable
private fun CentreArrow(
    icon: ImageVector,
    description: String,
    stepX: Float,
    stepY: Float,
    centre: RotationCenter,
    onCentre: (RotationCenter) -> Unit,
    modifier: Modifier,
) {
    fun stepped(from: RotationCenter) = RotationCenter(
        x = (from.x + stepX * CENTRE_STEP).coerceIn(0f, 1f),
        y = (from.y + stepY * CENTRE_STEP).coerceIn(0f, 1f),
    )

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val latest by rememberUpdatedState(centre)
    val commit by rememberUpdatedState(onCentre)

    // The tap is the click, so a screen reader activating this still moves the centre; holding only
    // takes over once it is clear the finger is staying put.
    //
    // While repeating, each step is taken from a value kept here rather than from [centre]. The
    // setting goes out through DataStore and comes back as a new flow value, which is slower than
    // the repeat — reading it back every tick would step from a stale position and stand still.
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(ARROW_REPEAT_DELAY_MILLIS)
        var running = latest
        while (true) {
            running = stepped(running)
            commit(running)
            delay(ARROW_REPEAT_MILLIS)
        }
    }
    Box(
        modifier
            .size(ARROW_SIZE)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = { commit(stepped(latest)) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(30.dp), tint = Color.White)
    }
}

/** A hundredth of the surface per tap: a few dp, fine enough to land on a particular icon. */
private const val CENTRE_STEP = 0.01f

private val ARROW_SIZE = 52.dp
private val ARROW_EDGE_GAP = 6.dp

/** Keeps the up arrow below the Filters/Centre row. */
private val ARROW_CLEAR_OF_TABS = 64.dp

private const val ARROW_REPEAT_DELAY_MILLIS = 380L
private const val ARROW_REPEAT_MILLIS = 90L

/**
 * The chosen point's coordinates, pinned to the point itself.
 *
 * The same numbers are spelled out on the panel below, but a finger placing the centre is looking at
 * the crosshair, not at the bottom of the screen. Read-only: this reports, and the artwork, the
 * arrows and the panel do the setting.
 *
 * Placed by a layout block rather than by an offset so the label can be centred on the point without
 * first measuring itself into state — and so it can flip below the crosshair, and pull back from the
 * screen's edges, rather than being clipped when the point is placed in a corner.
 */
@Composable
private fun CentreReadout(centre: RotationCenter, floor: Dp) {
    val density = LocalDensity.current
    // The tab row is drawn after this and so on top of it. Treat the space it occupies as unusable
    // rather than sliding the readout underneath it when the centre is placed high up.
    val ceiling = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + READOUT_CLEAR_OF_TABS
    Text(
        text = "${(centre.x * 100).roundToInt()}% · ${(centre.y * 100).roundToInt()}%",
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val gap = with(density) { READOUT_GAP.toPx() }
                    val pointX = centre.x * constraints.maxWidth
                    val pointY = centre.y * constraints.maxHeight
                    val above = pointY - gap - placeable.height
                    val room = { limit: Int, size: Int -> (limit - size).coerceAtLeast(0) }
                    // Stops above the down arrow as well as above the panel: the two are both
                    // centred, so a low centre would otherwise print the readout across the arrow.
                    val floorPx = with(density) { floor.toPx() }
                    val lowest = (constraints.maxHeight - floorPx - placeable.height)
                        .roundToInt()
                        .coerceIn(0, room(constraints.maxHeight, placeable.height))
                    val highest = with(density) { ceiling.toPx() }.roundToInt().coerceIn(0, lowest)
                    placeable.place(
                        x = (pointX - placeable.width / 2f).roundToInt()
                            .coerceIn(0, room(constraints.maxWidth, placeable.width)),
                        y = (if (above >= highest) above else pointY + gap).roundToInt()
                            .coerceIn(highest, lowest),
                    )
                }
            }
            .background(Color.Black.copy(alpha = 0.55f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("centre_readout"),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
    )
}

/** Clear of the crosshair's arms, which reach out to about 38 dp. */
private val READOUT_GAP = 52.dp

/** Height of the Filters/Centre row below the status bar, which the readout must not slide under. */
private val READOUT_CLEAR_OF_TABS = 56.dp

/** Breathing room between the readout and the down arrow below it. */
private val READOUT_GAP_TO_ARROW = 10.dp

/** Marks the chosen point without hiding the artwork under it. */
@Composable
private fun Crosshair(centre: RotationCenter) {
    val ring = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val point = Offset(centre.x * size.width, centre.y * size.height)
        val radius = 20.dp.toPx()
        drawCircle(
            Color.Black.copy(alpha = 0.55f),
            radius + 2.dp.toPx(),
            point,
            style = Stroke(5.dp.toPx()),
        )
        drawCircle(ring, radius, point, style = Stroke(2.5.dp.toPx()))
        val arms = listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f))
        arms.forEach { direction ->
            drawLine(
                color = ring,
                start = point + direction * (radius * 0.45f),
                end = point + direction * (radius * 1.9f),
                strokeWidth = 2.5.dp.toPx(),
            )
        }
    }
}
