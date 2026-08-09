package dev.paperouette.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.paperouette.wallpaper.data.PaperouetteSettings
import dev.paperouette.wallpaper.model.Remix

/** How far down the artwork the top scrim reaches, keeping the status bar icons legible. */
private val TOP_SCRIM_HEIGHT = 132.dp

/**
 * The artwork itself, filling the surface in the native shape this host supplied.
 *
 * This is the app's ground rather than a card inside it, so the renderer's own gestures — drag to
 * pan, two fingers for the next variant, three for the next piece — reach the whole screen. The
 * view is owned further up, in `PaperouetteApp`; this only draws it.
 */
@Composable
fun LiveStage(
    view: PaperouettePreviewView,
    remix: Remix,
    settings: PaperouetteSettings,
    darkMode: Boolean,
    paused: Boolean,
    batteryPaused: Boolean,
    onNextRemix: (Boolean) -> Unit,
    onNextDesign: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    playEndInset: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // The artwork's own loading colour, so the first frame arrives over the right ground
            // instead of over black.
            .background(Color(remix.colors.loadingColor))
            .testTag("live_stage")
            .semantics {
                contentDescription = "Live wallpaper"
                stateDescription = when {
                    batteryPaused -> "Paused for battery saver"
                    settings.stillArtwork -> "Held still"
                    settings.animationSpeed <= 0f -> "Animation still; touch active"
                    paused -> "Paused"
                    else -> "Animating"
                }
            },
    ) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.update(
                    scene = remix,
                    settings = settings,
                    pauseForBattery = paused,
                    isDarkMode = darkMode,
                    onNextRemix = onNextRemix,
                    onNextDesign = onNextDesign,
                )
            },
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(TOP_SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )
        if (onPlay != null) {
            // Its own colours rather than the theme's: this is the one control drawn straight onto
            // the artwork, so it cannot rely on a panel behind it for contrast.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = playEndInset)
                    .statusBarsPadding()
                    .padding(10.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(role = Role.Button, onClick = onPlay)
                    .testTag("enter_play"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Fullscreen,
                    contentDescription = "Play full screen",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White,
                )
            }
        }
    }
}
