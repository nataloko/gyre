package dev.paperouette.wallpaper.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.paperouette.wallpaper.data.CatalogSnapshot
import dev.paperouette.wallpaper.data.PaperouetteSettings
import dev.paperouette.wallpaper.data.ImageSource
import dev.paperouette.wallpaper.data.ImportManifest
import dev.paperouette.wallpaper.data.ImportProgress
import dev.paperouette.wallpaper.data.ImportedCatalog
import dev.paperouette.wallpaper.model.Design
import dev.paperouette.wallpaper.model.Remix
import dev.paperouette.wallpaper.ui.theme.PaperouetteMotion
import kotlinx.coroutines.delay

/** Everything above the collection: what is on the stage now, and what to do with it. */
val SHEET_HANDLE_HEIGHT = 20.dp
private val ACTION_HEIGHT = 52.dp
private val ACTION_GAP = 10.dp
private val STRIP_TILE_WIDTH = 64.dp
private val STRIP_TILE_HEIGHT = 84.dp
private val STRIP_PADDING = 13.dp
private val SHEET_EDGE = 20.dp

/** The tiles reflow to as many columns as fit at this width. */
private val PIECE_TILE_MIN_WIDTH = 150.dp

/** The bar that closes the bundled run off from the imports. Thicker than the sheet handle. */
private val SECTION_BAR_HEIGHT = 5.dp

/**
 * How far the tiles either side of the current one are drawn back from it.
 *
 * Applied as a scale rather than as a size: a lazy row is as tall as the tallest tile it can see, so
 * making the chosen one physically bigger meant the whole strip rose by half the difference the
 * moment that tile scrolled out of view, and settled back when it returned.
 */
private const val STRIP_UNSELECTED_SCALE = 0.94f

private val SHEET_PEEK_BOTTOM_GAP = 6.dp

/**
 * The height of everything that stays on screen when the sheet is at rest.
 *
 * The scaffold needs this as a number rather than as a measurement, so it is the sum of the parts
 * below and [SheetPeekContent] must draw exactly those parts. Six dp too many and the collection's
 * first row shows above the fold; six too few and the strip is clipped.
 */
val SHEET_PEEK_CONTENT_HEIGHT: Dp =
    SHEET_HANDLE_HEIGHT + ACTION_HEIGHT + ACTION_GAP + ACTION_HEIGHT +
        STRIP_TILE_HEIGHT + STRIP_PADDING * 2 + SHEET_PEEK_BOTTOM_GAP

/**
 * Everything the sheet keeps on screen at rest: what is on the stage, what to do with it, and every
 * variant of it.
 *
 * Shared by both layouts — pulled up from the bottom on a phone, docked to the edge on anything
 * wider — so the two never drift apart.
 */
@Composable
fun SheetPeekContent(
    pieceLabel: String,
    variantLabel: String,
    favorite: Boolean,
    variants: List<Remix>,
    selectedVariantId: String,
    resolveArtwork: (String) -> ImageSource,
    stripState: LazyListState,
    onToggleFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onSetWallpaper: () -> Unit,
    onOpenLook: () -> Unit,
    onOpenBehaviour: () -> Unit,
    onSelectVariant: (Remix) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("sheet_peek")) {
        Column(Modifier.padding(horizontal = SHEET_EDGE)) {
            CurrentArtworkRow(
                pieceLabel = pieceLabel,
                variantLabel = variantLabel,
                favorite = favorite,
                onToggleFavorite = onToggleFavorite,
                onShuffle = onShuffle,
            )
            Spacer(Modifier.height(ACTION_GAP))
            StageActionRow(
                onSetWallpaper = onSetWallpaper,
                onOpenLook = onOpenLook,
                onOpenBehaviour = onOpenBehaviour,
            )
        }
        VariantStrip(
            variants = variants,
            selectedId = selectedVariantId,
            resolveArtwork = resolveArtwork,
            onSelect = onSelectVariant,
            state = stripState,
        )
        Spacer(Modifier.height(SHEET_PEEK_BOTTOM_GAP))
    }
}

@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(SHEET_HANDLE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

/** What is on the stage, and the two things most often done to it. */
@Composable
fun CurrentArtworkRow(
    pieceLabel: String,
    variantLabel: String,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(ACTION_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pieceLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                variantLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        PaperouetteIconAction(
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite) "Remove favourite" else "Add favourite",
            tint = if (favorite) MaterialTheme.colorScheme.primary else null,
            onClick = onToggleFavorite,
        )
        Spacer(Modifier.width(8.dp))
        PaperouetteIconAction(
            icon = Icons.Outlined.Shuffle,
            contentDescription = "Shuffle",
            modifier = Modifier.testTag("shuffle"),
            onClick = onShuffle,
        )
    }
}

/** Setting the wallpaper, and the two panels that tune it. */
@Composable
fun StageActionRow(
    onSetWallpaper: () -> Unit,
    onOpenLook: () -> Unit,
    onOpenBehaviour: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(ACTION_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperouettePrimaryAction(
            label = "Set wallpaper",
            icon = Icons.Outlined.Wallpaper,
            modifier = Modifier.weight(1f).testTag("set_wallpaper"),
            onClick = onSetWallpaper,
        )
        Spacer(Modifier.width(8.dp))
        PaperouetteIconAction(
            icon = Icons.Outlined.Contrast,
            contentDescription = "Look",
            modifier = Modifier.testTag("open_look"),
            onClick = onOpenLook,
        )
        Spacer(Modifier.width(8.dp))
        PaperouetteIconAction(
            icon = Icons.Outlined.Sensors,
            contentDescription = "Behaviour",
            modifier = Modifier.testTag("open_behaviour"),
            onClick = onOpenBehaviour,
        )
    }
}

/**
 * Every variant of the piece on the stage, without labels.
 *
 * The names are long and nearly all alike within a piece — the tile shows what actually differs.
 * The name of the one in use is already on the row above.
 */
@Composable
fun VariantStrip(
    variants: List<Remix>,
    selectedId: String,
    resolveArtwork: (String) -> ImageSource,
    onSelect: (Remix) -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = variants.indexOfFirst { it.id == selectedId }
    // Follows a selection made anywhere else — a two-finger tap on the artwork, a shuffle — but
    // leaves the strip alone when the chosen tile is already in view, so it never yanks under a
    // finger that is scrolling it.
    LaunchedEffect(selectedIndex, variants) {
        if (selectedIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selectedIndex }) {
            state.animateScrollToItem(selectedIndex.coerceAtLeast(0))
        }
    }
    LazyRow(
        // Fixed, so the row's height cannot follow whichever tiles happen to be on screen — and so
        // it is exactly the height SHEET_PEEK_CONTENT_HEIGHT counts on.
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_TILE_HEIGHT + STRIP_PADDING * 2)
            .testTag("variant_strip"),
        state = state,
        contentPadding = PaddingValues(horizontal = SHEET_EDGE, vertical = STRIP_PADDING),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(variants, key = Remix::id) { variant ->
            val selected = variant.id == selectedId
            val emphasis by animateFloatAsState(
                targetValue = if (selected) 1f else STRIP_UNSELECTED_SCALE,
                animationSpec = PaperouetteMotion.fastSpatial(),
                label = "stripTile",
            )
            ArtworkThumbnail(
                source = resolveArtwork(variant.previews.thumb),
                loadingColor = variant.colors.loadingColor,
                selected = selected,
                contentDescription = variant.label,
                modifier = Modifier
                    .size(width = STRIP_TILE_WIDTH, height = STRIP_TILE_HEIGHT)
                    .graphicsLayer { scaleX = emphasis; scaleY = emphasis },
                onClick = { onSelect(variant) },
            )
        }
    }
}

/**
 * The collection, revealed by pulling the sheet up: every piece, or only the variants kept as
 * favourites.
 *
 * Favourites is a filter rather than a place of its own — they are a subset of the same collection,
 * and giving them a destination made the app one tab wider for nothing.
 */
@Composable
fun CollectionBody(
    catalogue: CatalogSnapshot,
    settings: PaperouetteSettings,
    resolveArtwork: (String) -> ImageSource,
    activeDesignId: String,
    activeRemixId: String,
    favouritesOnly: Boolean,
    imports: List<ImportManifest>,
    importProgress: ImportProgress,
    onFavouritesOnly: (Boolean) -> Unit,
    onImportFile: () -> Unit,
    onImportFolder: () -> Unit,
    onRemoveImport: (String) -> Unit,
    onSelectDesign: (Design) -> Unit,
    onSelectVariant: (Remix) -> Unit,
    onToggleFavorite: (Remix) -> Unit,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val favourites = catalogue.remixes.filter { it.id in settings.favorites }
    // Imported pieces are grouped under the import that brought them rather than mixed in. They
    // have to be: a pack made from the artwork this catalogue was recreated from carries the very
    // same labels, so a flat grid shows two pieces called "Hooli Hoops" and no way to tell which
    // is which.
    val importedByImport = catalogue.designs.groupBy { ImportedCatalog.importIdOf(it.id) }
    val bundled = importedByImport[null].orEmpty()
    var choosing by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PIECE_TILE_MIN_WIDTH),
        modifier = modifier.fillMaxSize().testTag("collection_body"),
        state = gridState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "filter") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperouetteChip(
                    label = "Favourites",
                    selected = favouritesOnly,
                    icon = if (favouritesOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    modifier = Modifier.testTag("favourites_filter"),
                    onSelected = onFavouritesOnly,
                )
                Spacer(Modifier.width(8.dp))
                PaperouetteChip(
                    label = "Import",
                    selected = choosing,
                    icon = Icons.Outlined.FolderOpen,
                    modifier = Modifier.testTag("import_artwork"),
                    onSelected = { choosing = it },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        favouritesOnly -> "${favourites.size} kept"
                        imports.isNotEmpty() ->
                            "${bundled.size} pieces · ${imports.size} imported"
                        else -> "${catalogue.designs.size} pieces"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // A second row rather than two more chips in the first: on a narrow window the header
        // already carries the filter, the Import chip and the count, and a third chip pushes the
        // count off the end.
        if (choosing) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "import_choice") {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    PaperouetteChip(
                        label = "A file",
                        selected = false,
                        icon = Icons.Outlined.Article,
                        modifier = Modifier.testTag("import_file"),
                        onSelected = { choosing = false; onImportFile() },
                    )
                    Spacer(Modifier.width(8.dp))
                    PaperouetteChip(
                        label = "A folder",
                        selected = false,
                        icon = Icons.Outlined.FolderOpen,
                        modifier = Modifier.testTag("import_folder"),
                        onSelected = { choosing = false; onImportFolder() },
                    )
                }
            }
        }

        // The import runs on the application scope, so it has to be visible wherever the user is
        // rather than behind whatever screen started it.
        if (importProgress !is ImportProgress.Idle) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "import_progress") {
                ImportStatus(importProgress, Modifier.padding(bottom = 10.dp))
            }
        }

        if (favouritesOnly && favourites.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Nothing kept yet", style = MaterialTheme.typography.titleMedium)
                    PaperouetteBodyText("Tap the heart while a variant is on the stage.")
                }
            }
        } else if (favouritesOnly) {
            items(favourites, key = Remix::id) { variant ->
                ArtworkTile(
                    label = variant.label,
                    thumb = variant.previews.thumb,
                    loadingColor = variant.colors.loadingColor,
                    selected = variant.id == activeRemixId,
                    favorite = true,
                    resolveArtwork = resolveArtwork,
                    onClick = { onSelectVariant(variant) },
                    onFavorite = { onToggleFavorite(variant) },
                )
            }
        } else {
            // An import that brought nothing showing is skipped entirely rather than guarded
            // inside the loop, so that "the first one" means the first one actually drawn and a
            // rule cannot be left standing over no tiles.
            val shown = imports.filter { importedByImport[it.id].orEmpty().isNotEmpty() }
            // The bundled run is not headed. It is what the collection is by default, so a label
            // over it would name the obvious; the bar below is what says where it ends.
            pieces(bundled, catalogue, activeDesignId, resolveArtwork, onSelectDesign)
            shown.forEachIndexed { index, manifest ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "import_${manifest.id}") {
                    // The bar, the heading and the pack's own name are one item rather than
                    // three: the grid spaces its children apart, and as separate items it would
                    // open that gap in the middle of what is meant to read as one break.
                    Column(Modifier.fillMaxWidth()) {
                        // A bar rather than a hairline: with the bundled run left unheaded this
                        // is the only thing marking where it ends, so it has to carry the break
                        // on its own. Pill-clipped like the sheet handle — nothing in the
                        // interface is half-rounded.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(SECTION_BAR_HEIGHT)
                                .clip(PillShape)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        // Once, over the imports as a group. Every pack gets the bar, because
                        // each is a boundary; only the first gets the word, because the packs
                        // below it are already named one by one.
                        if (index == 0) {
                            PaperouetteSectionLabel(
                                "Imported",
                                Modifier.testTag("collection_imported"),
                                top = 14.dp,
                            )
                        }
                        ImportHeader(manifest, onRemove = { onRemoveImport(manifest.id) })
                    }
                }
                pieces(
                    importedByImport[manifest.id].orEmpty(),
                    catalogue,
                    activeDesignId,
                    resolveArtwork,
                    onSelectDesign,
                )
            }
        }
    }
}

/** A named tile in the collection. */
@Composable
private fun ArtworkTile(
    label: String,
    thumb: String,
    loadingColor: Int,
    selected: Boolean,
    resolveArtwork: (String) -> ImageSource,
    onClick: () -> Unit,
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
) {
    // The name is part of the target, not a caption beside it: a tile whose label does nothing
    // when tapped is a tile that looks broken.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected },
    ) {
        Box {
            ArtworkThumbnail(
                source = resolveArtwork(thumb),
                loadingColor = loadingColor,
                selected = selected,
                contentDescription = null,
                // The artwork is square, so a square tile crops none of it away.
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (onFavorite != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClick = onFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (favorite) "Remove favourite" else "Add favourite",
                        Modifier.size(18.dp),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
        }
        Text(
            label,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A piece of artwork as a rounded tile.
 *
 * Selection is a ring rather than a tick or a lift: the tiles are all picture and no chrome, so a
 * badge drawn on top would sit on the artwork it is meant to be describing.
 */
@Composable
private fun ArtworkThumbnail(
    source: ImageSource,
    loadingColor: Int,
    selected: Boolean,
    contentDescription: String?,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(Color(loadingColor))
            .border(2.dp, ring, MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                this.selected = selected
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        AssetImage(source = source, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The one line an import gets.
 *
 * Determinate rather than a spinner: a 516-file pack takes long enough that "something is
 * happening" is not an answer to "how long". Failures say what went wrong in the same place,
 * since the sheet is where the user asked for the import.
 */
@Composable
private fun ImportStatus(progress: ImportProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("import_status")) {
        val message = when (progress) {
            is ImportProgress.Working -> "Importing ${progress.label} — ${progress.done} of ${progress.total}"
            is ImportProgress.Finished -> if (progress.skipped > 0) {
                "Imported ${progress.pieces} pieces; ${progress.skipped} could not be used"
            } else {
                "Imported ${progress.pieces} pieces"
            }
            is ImportProgress.Failed -> progress.reason
            ImportProgress.Idle -> ""
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (progress is ImportProgress.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress is ImportProgress.Working) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One row of tiles per design, shared by the bundled run and each import's. */
private fun LazyGridScope.pieces(
    designs: List<Design>,
    catalogue: CatalogSnapshot,
    activeDesignId: String,
    resolveArtwork: (String) -> ImageSource,
    onSelectDesign: (Design) -> Unit,
) {
    items(designs, key = Design::id) { piece ->
        val preview = catalogue.remixOrNull(piece.previewRemixId)
            ?: catalogue.remixesFor(piece.id).first()
        ArtworkTile(
            label = piece.label,
            thumb = preview.previews.thumb,
            loadingColor = preview.colors.loadingColor,
            selected = piece.id == activeDesignId,
            resolveArtwork = resolveArtwork,
            onClick = { onSelectDesign(piece) },
        )
    }
}

/**
 * What an import brought, and the way to take it back out.
 *
 * Removal lives on the thing being removed rather than on a settings page, and confirms in place
 * rather than in a dialog — the app has none, and one over full-screen artwork would be the first.
 */
@Composable
private fun ImportHeader(manifest: ImportManifest, onRemove: () -> Unit) {
    var confirming by remember(manifest.id) { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(REMOVE_CONFIRM_MILLIS)
            confirming = false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp).testTag("import_header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                manifest.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    plural(manifest.designs, "piece"),
                    plural(manifest.remixes, "variant"),
                    // The framework's own formatter, so the unit follows the size rather than
                    // rounding a small import down to "0 MB".
                    Formatter.formatShortFileSize(LocalContext.current, manifest.bytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            if (confirming) "Remove?" else "Remove",
            modifier = Modifier
                .clip(PillShape)
                .clickable(role = Role.Button) {
                    if (confirming) onRemove() else confirming = true
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("remove_import"),
            style = MaterialTheme.typography.labelLarge,
            color = if (confirming) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

/** How long "Remove?" waits before giving up and going back to "Remove". */
private const val REMOVE_CONFIRM_MILLIS = 3_000L
