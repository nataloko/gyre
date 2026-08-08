package dev.gyre.wallpaper.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gyre.wallpaper.data.ArtworkPaths
import dev.gyre.wallpaper.data.ArtworkImporter
import dev.gyre.wallpaper.data.CatalogRepository
import dev.gyre.wallpaper.data.ImportProgress
import dev.gyre.wallpaper.data.SettingsRepository
import dev.gyre.wallpaper.ui.theme.GyreMotion
import dev.gyre.wallpaper.ui.theme.GyreTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The panels that take over the screen. Only ever one of them. */
private enum class Overlay { NONE, LOOK, BEHAVIOUR }

/** Material's compact/medium boundary: at or above it the sheet docks to the side instead. */
private val WIDE_LAYOUT_BREAKPOINT = 600.dp

/** Wide enough for two tiles and the peek rows without crowding the stage beside it. */
private val SIDE_PANEL_WIDTH = 380.dp

private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Artwork left showing below the status bar when the sheet is all the way up. */
private val EXPANDED_STAGE_SLIVER = 44.dp

/** Panels are glass rather than paint, so a little of the artwork stays visible through them. */
private const val PANEL_ALPHA = 0.93f

/**
 * The app is the wallpaper, full size, with a sheet pulled over it.
 *
 * There are no destinations and no preview: the renderer is the ground the whole interface stands
 * on, so what you are looking at is what you would be wearing, at the size you would wear it. The
 * sheet at rest shows the piece on the stage and every variant of it; pulled up it becomes the
 * collection. Two panels tune what the stage is doing — [LookPanel] over the artwork, since its
 * settings are things you judge by eye, and [BehaviourPanel] over everything, since its are not.
 */
/** How long a finished import stays on screen before the status line clears itself. */
private const val IMPORT_NOTICE_MILLIS = 6_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GyreApp(
    catalogueRepository: CatalogRepository,
    repository: SettingsRepository,
    importer: ArtworkImporter,
    onApplyWallpaper: () -> Boolean,
    onChooseFile: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val selection by repository.selection.collectAsStateWithLifecycle()
    val importProgress by importer.progress.collectAsStateWithLifecycle()
    val imports by importer.imported.collectAsStateWithLifecycle()
    val darkMode = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val batterySaver by rememberBatterySaver()

    // The repository owns the "has the theme actually changed" test and the setting's own guard,
    // so this only reports the theme. Keying it on the setting too would re-report it on every
    // toggle, and switching the setting on is already handled where the switch is.
    LaunchedEffect(darkMode) { repository.selectForDarkMode(darkMode) }

    // An import goes to the end of the collection so nothing the user knows is renumbered, and
    // the app selects it instead — which puts it at the front of attention without putting it at
    // the front of the model. The notice clears itself; it is a status line, not a dialog.
    LaunchedEffect(importProgress) {
        val finished = importProgress as? ImportProgress.Finished ?: return@LaunchedEffect
        repository.selectDesign(finished.designId, darkMode)
        delay(IMPORT_NOTICE_MILLIS)
        importer.acknowledge()
    }

    // Collected, so the collection redraws when an import lands or is removed. The selection
    // flow resolves against the same catalogue, so these fallbacks are only reached in the instant
    // before that resolution arrives — but a composition must not throw in it.
    val catalogue by catalogueRepository.current.collectAsStateWithLifecycle()
    val activeRemix = catalogue.remixOrNull(selection.remixId) ?: catalogue.remixes.first()
    val activeDesign = catalogue.designOrNull(activeRemix.designId) ?: catalogue.designs.first()
    val variants = catalogue.remixesFor(activeDesign.id).ifEmpty { listOf(activeRemix) }

    // Owned at the root of the app, so it is never disposed while the app is open. Disposing it
    // rebuilds a thread, an EGL context and every decoded 2600x2600 layer.
    val context = LocalContext.current
    // Resolution is a pure function of the catalogue path, so it is remembered against the
    // context alone rather than rebuilt whenever the catalogue changes.
    val resolveArtwork = remember(context) { { path: String -> ArtworkPaths.resolve(path, context) } }
    val previewView = remember(context) { GyrePreviewView(context) }
    DisposableEffect(previewView) { onDispose(previewView::release) }

    GyreTheme(palette = activeRemix.colors) {
        var overlayName by rememberSaveable { mutableStateOf(Overlay.NONE.name) }
        val overlay = Overlay.valueOf(overlayName)
        var favouritesOnly by rememberSaveable { mutableStateOf(false) }
        val stripState = rememberLazyListState()
        val gridState = rememberLazyGridState()
        val behaviourState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }
        // skipHiddenState = false so the sheet has somewhere to go: pushing it off the bottom is
        // what puts the app in play (see [playing] below), and it is the gesture people try first.
        val sheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = false,
        )
        val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
        var playing by rememberSaveable { mutableStateOf(false) }

        val batteryPaused = batterySaver && settings.pauseOnBatterySaver
        // Nothing of the artwork survives behind the behaviour panel, so stop drawing it.
        val stagePaused = batteryPaused || overlay == Overlay.BEHAVIOUR

        ImmersiveWhile(playing)

        BackHandler(
            enabled = overlay != Overlay.NONE ||
                playing ||
                sheetState.currentValue == SheetValue.Expanded,
        ) {
            when {
                overlay != Overlay.NONE -> overlayName = Overlay.NONE.name
                playing -> playing = false
                else -> scope.launch { sheetState.partialExpand() }
            }
        }

        val stage = @Composable { modifier: Modifier ->
            LiveStage(
                view = previewView,
                remix = activeRemix,
                settings = settings,
                darkMode = darkMode,
                paused = stagePaused,
                batteryPaused = batteryPaused,
                onNextRemix = { isDark -> scope.launch { repository.nextRemix(isDark) } },
                onNextDesign = { isDark -> scope.launch { repository.nextDesign(isDark) } },
                modifier = modifier,
                // Hidden under an open panel as well as in play: the panels leave the top of the
                // stage showing, and it would sit on top of their own close button.
                onPlay = if (playing || overlay != Overlay.NONE) null else ({ playing = true }),
            )
        }

        val look = @Composable {
            OverlayTransition(visible = overlay == Overlay.LOOK) {
                LookPanel(
                    artwork = activeRemix,
                    settings = settings,
                    onUpdate = { transform -> scope.launch { repository.update(transform) } },
                    onClose = { overlayName = Overlay.NONE.name },
                )
            }
        }

        val peek = @Composable {
            SheetPeekContent(
                pieceLabel = activeDesign.label,
                variantLabel = activeRemix.label,
                favorite = activeRemix.id in settings.favorites,
                variants = variants,
                selectedVariantId = activeRemix.id,
                resolveArtwork = resolveArtwork,
                stripState = stripState,
                onToggleFavorite = { scope.launch { repository.toggleFavorite(activeRemix.id) } },
                onShuffle = { scope.launch { repository.shuffle(darkMode) } },
                onSetWallpaper = {
                    if (!onApplyWallpaper()) {
                        scope.launch {
                            snackbarHostState.showSnackbar("No live wallpaper picker is available")
                        }
                    }
                },
                onOpenLook = { overlayName = Overlay.LOOK.name },
                onOpenBehaviour = { overlayName = Overlay.BEHAVIOUR.name },
                onSelectVariant = { variant ->
                    scope.launch { repository.selectRemix(variant.id, darkMode) }
                },
            )
        }

        val collection = @Composable { modifier: Modifier, contentPadding: PaddingValues ->
            CollectionBody(
                catalogue = catalogue,
                settings = settings,
                resolveArtwork = resolveArtwork,
                activeDesignId = activeDesign.id,
                activeRemixId = activeRemix.id,
                favouritesOnly = favouritesOnly,
                imports = imports.map { it.manifest },
                importProgress = importProgress,
                onFavouritesOnly = { favouritesOnly = it },
                onImportFile = onChooseFile,
                onImportFolder = onChooseFolder,
                onRemoveImport = { id -> scope.launch { importer.remove(id) } },
                onSelectDesign = { piece ->
                    scope.launch { repository.selectDesign(piece.id, darkMode) }
                },
                onSelectVariant = { variant ->
                    scope.launch { repository.selectRemix(variant.id, darkMode) }
                },
                onToggleFavorite = { variant -> scope.launch { repository.toggleFavorite(variant.id) } },
                gridState = gridState,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                if (maxWidth >= WIDE_LAYOUT_BREAKPOINT) {
                    // A wide window is a short one: the sheet would leave the stage a letterbox, so
                    // the same content docks to the end edge instead and the stage keeps its height.
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            stage(Modifier.fillMaxSize())
                            // Confined to the stage, because the centre of rotation it places is a
                            // fraction of the surface being rendered, not of the window.
                            look()
                        }
                        // In play the panel is not composed at all rather than slid away: there is
                        // no sheet here to push off an edge, and the stage simply takes the room.
                        if (!playing) {
                            Column(
                                Modifier
                                    .width(SIDE_PANEL_WIDTH)
                                    .fillMaxHeight()
                                    .testTag("side_panel")
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
                                    )
                                    .statusBarsPadding()
                                    .navigationBarsPadding(),
                            ) {
                                peek()
                                collection(
                                    Modifier.weight(1f),
                                    PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                                )
                            }
                        }
                    }
                } else {
                    // Play and the sheet's position are the same fact, so they are kept in step in
                    // both directions: the button drives the sheet away, and dragging the sheet away
                    // starts play. Each effect is idempotent, so they settle rather than fight.
                    LaunchedEffect(playing) {
                        if (playing) {
                            sheetState.hide()
                        } else if (sheetState.currentValue == SheetValue.Hidden) {
                            sheetState.partialExpand()
                        }
                    }
                    LaunchedEffect(sheetState) {
                        snapshotFlow { sheetState.currentValue }
                            .collect { playing = it == SheetValue.Hidden }
                    }
                    // The Look panel is judged against the artwork behind it, so the collection
                    // cannot be left standing in front of it. Opening Look drops the sheet back to
                    // its resting height, which is exactly the footprint the panel then covers.
                    LaunchedEffect(overlay) {
                        if (overlay == Overlay.LOOK) sheetState.partialExpand()
                    }

                    // Read out here: inside the sheet's own ColumnScope, maxHeight is the column's.
                    val expandedSheetHeight = maxHeight - topInset - EXPANDED_STAGE_SLIVER
                    BottomSheetScaffold(
                        scaffoldState = scaffoldState,
                        // Taken out from behind the Look panel altogether, rather than merely
                        // covered by it. The panel then only has to be as tall as its own controls,
                        // which is what lets the centre of rotation stay visible near the bottom.
                        sheetPeekHeight = if (overlay == Overlay.LOOK) {
                            0.dp
                        } else {
                            SHEET_PEEK_CONTENT_HEIGHT + bottomInset
                        },
                        sheetShape = SHEET_SHAPE,
                        sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
                        sheetContentColor = MaterialTheme.colorScheme.onSurface,
                        sheetTonalElevation = 0.dp,
                        sheetShadowElevation = 0.dp,
                        sheetDragHandle = { SheetHandle() },
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        snackbarHost = {},
                        sheetContent = {
                            // Bounded rather than fillMaxHeight: the sheet's expanded position is
                            // its content's height, and a full-height sheet would put the piece's
                            // name behind the status bar and leave nothing of the stage in view.
                            Column(Modifier.height(expandedSheetHeight)) {
                                peek()
                                // The inset belongs between the peek and the collection, not below
                                // the collection: it is what the sheet at rest ends on, so without
                                // it the first row of the collection shows above the fold.
                                Spacer(Modifier.height(bottomInset))
                                collection(
                                    Modifier.weight(1f),
                                    PaddingValues(
                                        start = 20.dp,
                                        end = 20.dp,
                                        bottom = bottomInset + 24.dp,
                                    ),
                                )
                            }
                        },
                        // The stage deliberately ignores the padding the scaffold offers: it runs
                        // under the sheet, which is the whole idea.
                        content = { stage(Modifier.fillMaxSize()) },
                    )
                    look()
                }
            }

            OverlayTransition(visible = overlay == Overlay.BEHAVIOUR) {
                BehaviourPanel(
                    settings = settings,
                    darkMode = darkMode,
                    onUpdate = { transform -> scope.launch { repository.update(transform) } },
                    onAutomaticDarkChanged = { enabled ->
                        scope.launch {
                            repository.update { it.copy(automaticDarkVariants = enabled) }
                            // Not selectDesign: that returns to the design's tile artwork, which
                            // would throw away the variant the user is looking at.
                            if (enabled) repository.applyDarkMode(darkMode)
                        }
                    },
                    onClose = { overlayName = Overlay.NONE.name },
                    listState = behaviourState,
                )
            }

            PlayHint(playing, Modifier.align(Alignment.BottomCenter))

            SnackbarHost(
                snackbarHostState,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

/**
 * Hides the status and navigation bars while the app is in play, and gives them back afterwards.
 *
 * Left to swipe from an edge rather than to a tap, because a tap on the stage is the artwork's own
 * — with Nudge on it is what sets the thing spinning.
 */
@Composable
private fun ImmersiveWhile(playing: Boolean) {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(playing, activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (playing) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        // Leaving the app in play must not leave the phone without its bars.
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Says how to get back, once, where the sheet just left from — then gets out of the way itself.
 *
 * Play has no exit drawn on it by design, so something has to name the way out the first time.
 */
@Composable
private fun PlayHint(playing: Boolean, modifier: Modifier) {
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        showing = playing
        if (playing) {
            delay(PLAY_HINT_MILLIS)
            showing = false
        }
    }
    AnimatedVisibility(
        visible = showing,
        modifier = modifier.padding(bottom = 56.dp),
        enter = fadeIn(GyreMotion.effects()),
        exit = fadeOut(GyreMotion.effects()),
    ) {
        Text(
            "Back brings the controls back",
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), PillShape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

private const val PLAY_HINT_MILLIS = 2600L

/** Panels rise into place rather than appearing, so it reads as a layer over the stage. */
@Composable
private fun OverlayTransition(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(GyreMotion.effects()) +
            slideInVertically(GyreMotion.spatial()) { height -> height / 10 },
        exit = fadeOut(GyreMotion.effects()) +
            slideOutVertically(GyreMotion.spatial()) { height -> height / 10 },
    ) {
        content()
    }
}

@Composable
private fun rememberBatterySaver(): State<Boolean> {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val state = remember { mutableStateOf(powerManager.isPowerSaveMode) }
    DisposableEffect(context, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                state.value = powerManager.isPowerSaveMode
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
