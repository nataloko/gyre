package dev.gyre.wallpaper.render

import dev.gyre.wallpaper.model.Layer
import dev.gyre.wallpaper.model.PaletteColors
import dev.gyre.wallpaper.model.PreviewAssets
import dev.gyre.wallpaper.model.Remix
import dev.gyre.wallpaper.model.RotationDirection
import dev.gyre.wallpaper.model.RotationSpec
import dev.gyre.wallpaper.model.SubsetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SceneCoverageTest {
    @Test
    fun scaledWindowNeverLeavesTheImageWhileRotating() {
        for (viewportAspect in VIEWPORT_ASPECTS) {
            for (subset in SUBSETS) {
                for (pivot in PIVOTS) {
                    val (visibleX, visibleY) = coverFit(viewportAspect)
                    val window = SceneCoverage.fit(
                        visibleX,
                        visibleY,
                        subset,
                        PARALLAX_RESERVE,
                        pivot,
                    )

                    forEachWindowCorner(window.x, window.y, window.panAllowance, pivot) { x, y ->
                        assertTrue(
                            "aspect $viewportAspect pivot $pivot corner ($x, $y) left $subset",
                            inside(x, y, subset),
                        )
                    }
                }
            }
        }
    }

    /**
     * The pan allowance a scene reports has to be small enough that using all of it still leaves
     * the window inside the artwork — a rotation centre near an edge leaves less room than the
     * nominal parallax amount, and spending the nominal amount anyway would expose an edge.
     */
    @Test
    fun panningToTheReportedAllowanceStaysInsideTheImage() {
        for (viewportAspect in VIEWPORT_ASPECTS) {
            for (pivot in PIVOTS) {
                val window = SceneCoverage.window(
                    scene = rotatingScene(),
                    sceneAspect = 1f,
                    viewportAspect = viewportAspect,
                    pivot = pivot,
                )

                forEachWindowCorner(window.x, window.y, window.panAllowance, pivot) { x, y ->
                    assertTrue(
                        "aspect $viewportAspect pivot $pivot corner ($x, $y) left the artwork " +
                            "while panning by ${window.panAllowance}",
                        inside(x, y, SubsetLayout.Full),
                    )
                }
            }
        }
    }

    /**
     * The launcher's zoom only ever closes the window in, so a window that was already safe stays
     * safe. Swept the same way as the plain framing, because that is the claim being made.
     */
    @Test
    fun aZoomedWindowNeverLeavesTheImageEither() {
        for (viewportAspect in VIEWPORT_ASPECTS) {
            for (pivot in PIVOTS) {
                for (zoom in ZOOMS) {
                    val window = SceneCoverage.zoomed(
                        SceneCoverage.window(
                            scene = rotatingScene(),
                            sceneAspect = 1f,
                            viewportAspect = viewportAspect,
                            pivot = pivot,
                        ),
                        zoom,
                    )

                    forEachWindowCorner(window.x, window.y, window.panAllowance, pivot) { x, y ->
                        assertTrue(
                            "aspect $viewportAspect pivot $pivot zoom $zoom corner ($x, $y) " +
                                "left the artwork",
                            inside(x, y, SubsetLayout.Full),
                        )
                    }
                }
            }
        }
    }

    /** Zoom is a transient the launcher holds; at rest the framing must be untouched. */
    @Test
    fun noZoomLeavesTheWindowExactlyAsItWas() {
        val window = SceneCoverage.fit(0.45f, 1f, SubsetLayout.Full, PARALLAX_RESERVE)

        assertEquals(window, SceneCoverage.zoomed(window, 0f))
        assertEquals(window, SceneCoverage.zoomed(window, Float.NaN))
    }

    @Test
    fun zoomingShowsLessOfTheArtwork() {
        val resting = SceneCoverage.fit(0.45f, 1f, SubsetLayout.Full, PARALLAX_RESERVE)
        val drawerOpen = SceneCoverage.zoomed(resting, 1f)

        assertTrue("a zoomed window must be smaller", drawerOpen.y < resting.y)
        assertEquals(resting.y * (1f - SceneCoverage.ZOOM_DEPTH), drawerOpen.y, 0.0001f)
        // Granted against the larger window, so carrying it over is the conservative choice.
        assertEquals(resting.panAllowance, drawerOpen.panAllowance, 0f)
    }

    @Test
    fun aCentreOfRotationNearAScreenEdgeCropsHarder() {
        val middle = SceneCoverage.fit(0.45f, 1f, SubsetLayout.Full, PARALLAX_RESERVE)
        val edge = SceneCoverage.fit(
            0.45f,
            1f,
            SubsetLayout.Full,
            PARALLAX_RESERVE,
            RotationCenter(0.5f, 0f),
        )
        val corner = SceneCoverage.fit(
            0.45f,
            1f,
            SubsetLayout.Full,
            PARALLAX_RESERVE,
            RotationCenter(0f, 0f),
        )

        assertTrue("an edge must crop harder than the middle", edge.y < middle.y)
        assertTrue("a corner must crop hardest of all", corner.y < edge.y)
        // The far corner is then a whole screen away instead of half of one.
        assertEquals(middle.y / 2f, corner.y, 0.0001f)
    }

    @Test
    fun squarerScreensGiveUpMoreOfTheImage() {
        val (portraitX, portraitY) = coverFit(1080f / 2400f)
        val (squareX, squareY) = coverFit(1f)

        val portrait = SceneCoverage.fit(portraitX, portraitY, SubsetLayout.Full, PARALLAX_RESERVE)
        val square = SceneCoverage.fit(squareX, squareY, SubsetLayout.Full, PARALLAX_RESERVE)

        assertEquals(0.854f, portrait.y / portraitY, 0.001f)
        assertTrue(
            "a square screen must crop harder than a tall one",
            square.y / squareY < portrait.y / portraitY,
        )
    }

    @Test
    fun aWindowThatAlreadyFitsIsLeftAlone() {
        val window = SceneCoverage.fit(0.1f, 0.1f, SubsetLayout.Full, 0f)

        assertEquals(0.1f, window.x, 0.0001f)
        assertEquals(0.1f, window.y, 0.0001f)
    }

    @Test
    fun panningNeverClaimsMoreThanHalfTheRadius() {
        val greedy = SceneCoverage.fit(0.45f, 1f, SubsetLayout.Full, 10f)
        val half = SceneCoverage.fit(0.45f, 1f, SubsetLayout.Full, 0.25f)

        assertEquals(half.y, greedy.y, 0.0001f)
    }

    /** The renderer's cover-fit against the square artwork, where the scene aspect is 1. */
    private fun coverFit(viewportAspect: Float): Pair<Float, Float> =
        if (1f > viewportAspect) viewportAspect to 1f else 1f to 1f / viewportAspect

    /** A single full-frame rotating layer, the shape 142 of the 208 bundled remixes take. */
    private fun rotatingScene() = Remix(
        id = "test_scene",
        designId = "test",
        label = "Test",
        isDark = false,
        isMultilayered = false,
        type = "parallax",
        previews = PreviewAssets(thumb = "assets/artwork/test.webp"),
        layers = listOf(
            Layer(
                imageUrl = "assets/artwork/test.webp",
                type = "animated",
                parallaxScale = 0.4f,
                rotation = RotationSpec(200f, RotationDirection.CLOCKWISE),
            ),
        ),
        colors = PaletteColors(loadingColor = 0),
    )

    private fun forEachWindowCorner(
        spanX: Float,
        spanY: Float,
        pan: Float,
        pivot: RotationCenter,
        check: (Float, Float) -> Unit,
    ) {
        for (degrees in 0 until 360) {
            val radians = degrees * PI.toFloat() / 180f
            for (panDegrees in 0 until 360 step 45) {
                val panRadians = panDegrees * PI.toFloat() / 180f
                val panX = pan * cos(panRadians)
                val panY = pan * sin(panRadians)
                // Matches the layer shader: screen corners measured from the pivot, panned, then
                // turned about it, and finally placed against the middle of the artwork.
                for (screenX in listOf(0f, 1f)) {
                    for (screenY in listOf(0f, 1f)) {
                        val offsetX = (screenX - pivot.x) * spanX + panX
                        val offsetY = (screenY - pivot.y) * spanY + panY
                        check(
                            cos(radians) * offsetX - sin(radians) * offsetY + 0.5f,
                            sin(radians) * offsetX + cos(radians) * offsetY + 0.5f,
                        )
                    }
                }
            }
        }
    }

    private fun inside(x: Float, y: Float, subset: SubsetLayout): Boolean =
        x >= subset.xRatio - TOLERANCE &&
            x <= subset.xRatio + subset.sceneWidthRatio + TOLERANCE &&
            y >= subset.yRatio - TOLERANCE &&
            y <= subset.yRatio + subset.sceneHeightRatio + TOLERANCE

    private companion object {
        const val TOLERANCE = 0.0001f

        /** The pan headroom the renderer reserves for an animated layer. */
        const val PARALLAX_RESERVE = 0.032f

        val VIEWPORT_ASPECTS = listOf(0.42f, 1080f / 2400f, 0.6f, 0.75f, 1f, 1.8f, 2400f / 1080f)

        /** Launcher zoom, from the home screen through to the app drawer. */
        val ZOOMS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** Screen positions for the centre of rotation, including every corner — the worst case. */
        val PIVOTS = listOf(
            RotationCenter.Center,
            RotationCenter(0.5f, 0.35f),
            RotationCenter(0.7f, 0.5f),
            RotationCenter(0.25f, 0.75f),
            RotationCenter(0.5f, 0f),
            RotationCenter(0f, 0.5f),
            RotationCenter(0f, 0f),
            RotationCenter(1f, 1f),
        )

        /**
         * Full-frame layers plus two off-square subset layouts. The bundled catalogue no longer
         * ships any subset layers, but the geometry must keep holding for them: the schema still
         * admits them, and these two are the shapes the imported catalogue once used.
         */
        val SUBSETS = listOf(
            SubsetLayout.Full,
            SubsetLayout(0.0015385f, 0.0353846f, 0.9984615f, 0.9280769f),
            SubsetLayout(0.0015385f, 0.0130769f, 0.9984615f, 0.9738462f),
        )
    }
}
