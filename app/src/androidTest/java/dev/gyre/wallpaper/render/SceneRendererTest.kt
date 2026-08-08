package dev.gyre.wallpaper.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gyre.wallpaper.data.ArtworkPaths
import dev.gyre.wallpaper.data.BundledCatalogRepository
import dev.gyre.wallpaper.data.openStream
import dev.gyre.wallpaper.model.Remix
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val catalogue = BundledCatalogRepository(context).current.value

    /**
     * The fixtures come out of the same maths that draws the artwork, so the renderer and the
     * reference should agree to within resampling and the layers' lossy encoding. These bounds
     * were set when the swirl catalogue landed and have not yet been tightened against measured
     * on-device headroom; once measured, tighten them to just above it — and never loosen one to
     * make a change pass. The line-art stacks (Planetarium's comets, Truchet's jewels) get the
     * loosest bounds because their strokes are a pixel or two wide, which is where a mipmap and
     * a resample disagree most.
     */
    @Test
    fun representativeZeroMotionScenesMatchBundledPreviews() {
        val cases = listOf(
            VisualLimit("afterglow_hue_biolume", 0.030f, 0.960f),
            VisualLimit("planetarium_hue_golden", 0.070f, 0.880f),
            VisualLimit("quasicrystal_hue_penrose", 0.050f, 0.930f),
            VisualLimit("truchet_hue_circuit", 0.070f, 0.880f),
        )

        OffscreenRenderer(context).use { renderer ->
            cases.forEach { limit ->
                val remix = catalogue.remix(limit.remixId)
                val rendered = renderer.render(remix)
                val preview = loadCenterCroppedPreview(remix, rendered.width, rendered.height)
                val error = rendered.meanAbsoluteRgbError(preview)
                val ssim = rendered.structuralSimilarity(preview)
                Log.i("GyreVisual", "${remix.id}: meanRgb=$error ssim=$ssim")
                assertTrue("${remix.id} mean RGB error was $error", error < limit.maxMeanRgbError)
                assertTrue("${remix.id} SSIM was $ssim", ssim > limit.minSsim)
            }
        }
    }

    /**
     * Mirroring reflects the mapping about the pivot, so with the pivot in its usual place that is
     * the middle of the screen and the frame should come out as the plain flip of the unmirrored
     * one. Anything else means the mirror is moving the artwork as well as reflecting it.
     */
    @Test
    fun mirroringRendersTheReflectionOfTheSameFrame() {
        val remix = catalogue.remix("taffy_hue_sorbet")
        OffscreenRenderer(context).use { renderer ->
            val plain = renderer.render(remix)
            val mirrored = renderer.render(remix, mirrored = true)

            assertTrue(
                "mirroring must change what is rendered",
                plain.meanAbsoluteRgbError(mirrored) > 0.01f,
            )
            val error = mirrored.meanAbsoluteRgbError(plain.flippedHorizontally())
            Log.i("GyreVisual", "mirror versus flip error=$error")
            assertTrue("mirrored frame is not the reflection: error $error", error < 0.02f)
        }
    }

    /**
     * The framing guarantee is a distance from the middle of the artwork, and reflection preserves
     * every distance, so a mirrored scene must keep its edges out of view exactly as before — even
     * from an off-centre pivot, where the crop is tightest.
     */
    @Test
    fun aMirroredSceneStillKeepsItsEdgesOutOfView() {
        val remix = catalogue.remix("taffy_hue_sorbet")
        OffscreenRenderer(context).use { renderer ->
            for (step in 0 until 5) {
                val frame = renderer.render(
                    remix = remix,
                    rotationCenter = RotationCenter(0.34f, 0.62f),
                    monotonicNanos = step * 48_000_000_000L,
                    mirrored = true,
                )
                val cleared = frame.fractionMatching(CLEAR_COLOR)
                Log.i("GyreVisual", "mirrored step $step cleared=$cleared")
                assertTrue(
                    "background showed through on ${(cleared * 100).toInt()}% of the frame",
                    cleared < MAX_CLEARED_FRACTION,
                )
            }
        }
    }

    /**
     * Reversing runs the scene's own animation backwards, so a frame at time t with it on must be
     * the frame at `period - t` with it off. `kleinian_hue_indra` is a single layer on a 420
     * second clockwise turn, so one period covers the whole scene and the identity is exact.
     */
    @Test
    fun reversingRunsTheSameAnimationBackwards() {
        val remix = catalogue.remix("kleinian_hue_indra")
        val period = 420L * 1_000_000_000L
        val quarter = period / 4

        OffscreenRenderer(context).use { renderer ->
            val forward = renderer.render(remix, monotonicNanos = quarter)
            val reversed = renderer.render(remix, monotonicNanos = quarter, rotationReversed = true)
            val mirrorOfTime = renderer.render(remix, monotonicNanos = period - quarter)

            assertTrue(
                "reversing must change what is rendered",
                forward.meanAbsoluteRgbError(reversed) > 0.01f,
            )
            val error = reversed.meanAbsoluteRgbError(mirrorOfTime)
            Log.i("GyreVisual", "reversed versus rewound error=$error")
            assertTrue("reversed frame is not the scene rewound: error $error", error < 0.02f)
        }
    }

    /**
     * Reverse's whole claim is that the artwork turns the other way, and nothing asserted that.
     *
     * The frame-equality test beside this one says `reversed(t)` is `forward(period - t)`, which is
     * the right identity but says nothing a reader can point at on screen. This measures the thing
     * the setting is named for: sample a ring of pixels about the centre, correlate it against the
     * same ring a while later, and read off which way the pattern travelled. On a piece whose
     * animation is invisible — anything rotationally symmetric — both numbers come back at zero,
     * which is worth catching too.
     */
    @Test
    fun reverseTurnsTheArtworkTheOtherWay() {
        // A single one-armed spiral on a 180 second turn, so 15 seconds is 30 degrees: far
        // enough to measure, and nothing short of a half turn is ambiguous.
        val remix = catalogue.remix("afterglow_hue_biolume")
        val step = 15L * 1_000_000_000L

        OffscreenRenderer(context).use { renderer ->
            fun travel(reversed: Boolean): Float {
                val before = renderer.render(remix, monotonicNanos = 0L, rotationReversed = reversed)
                val after = renderer.render(remix, monotonicNanos = step, rotationReversed = reversed)
                return angularTravelDegrees(before.ringProfile(), after.ringProfile())
            }

            val forward = travel(reversed = false)
            val reversed = travel(reversed = true)
            Log.i("GyreVisual", "travel forward=$forward reversed=$reversed")

            assertTrue("the artwork does not visibly turn at all", kotlin.math.abs(forward) > 5f)
            assertTrue(
                "reverse did not turn it the other way: forward $forward, reversed $reversed",
                forward * reversed < 0f,
            )
        }
    }

    /** Luma around a circle centred on the artwork, as a signal to correlate. */
    private fun PixelFrame.ringProfile(samples: Int = 720): FloatArray {
        val centreX = width / 2f
        val centreY = height / 2f
        val radius = kotlin.math.min(width, height) * 0.32f
        return FloatArray(samples) { index ->
            val angle = index * 2.0 * Math.PI / samples
            val x = (centreX + radius * kotlin.math.cos(angle)).toInt().coerceIn(0, width - 1)
            val y = (centreY + radius * kotlin.math.sin(angle)).toInt().coerceIn(0, height - 1)
            val pixel = pixels[y * width + x]
            (pixel shr 16 and 0xff) * 0.2126f +
                (pixel shr 8 and 0xff) * 0.7152f +
                (pixel and 0xff) * 0.0722f
        }
    }

    /**
     * How far [after] is rotated from [before], in degrees, signed.
     *
     * Searched only within a quarter turn either way: a piece with N arms repeats every 360/N, so
     * a wider search finds the same peak again on the next arm and the sign becomes a coin toss.
     */
    private fun angularTravelDegrees(before: FloatArray, after: FloatArray): Float {
        val samples = before.size
        val beforeMean = before.average().toFloat()
        val afterMean = after.average().toFloat()
        val limit = samples / 4
        var bestShift = 0
        var bestScore = -Float.MAX_VALUE
        for (shift in -limit..limit) {
            var score = 0f
            for (index in 0 until samples) {
                val other = ((index + shift) % samples + samples) % samples
                score += (before[index] - beforeMean) * (after[other] - afterMean)
            }
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }
        return bestShift * 360f / samples
    }

    /**
     * A scene turning about an off-centre point still has to fill the screen. The geometry is
     * proved exhaustively in `SceneCoverageTest`; this checks the shader agrees with it, by looking
     * for the cleared background showing through at a spread of rotation angles.
     */
    @Test
    fun anOffCentreRotationCentreStillFillsTheScreen() {
        val remix = catalogue.remix("taffy_hue_sorbet")
        val offCentre = RotationCenter(0.34f, 0.62f)
        OffscreenRenderer(context).use { renderer ->
            val centred = renderer.render(remix)

            // A fifth of the rotation period apart, so the sweep covers a full turn.
            for (step in 0 until 5) {
                val frame = renderer.render(
                    remix = remix,
                    rotationCenter = offCentre,
                    monotonicNanos = step * 48_000_000_000L,
                )
                val cleared = frame.fractionMatching(CLEAR_COLOR)
                Log.i("GyreVisual", "offCentre step $step cleared=$cleared")
                assertTrue(
                    "background showed through on ${(cleared * 100).toInt()}% of the frame",
                    cleared < MAX_CLEARED_FRACTION,
                )
            }

            assertTrue(
                "moving the rotation centre must change what is rendered",
                centred.meanAbsoluteRgbError(renderer.render(remix, rotationCenter = offCentre)) >
                    0.01f,
            )
        }
    }

    /** Landscape is framed natively too; an off-centre turn must not expose the clear colour. */
    @Test
    fun aLandscapeOffCentreRotationCentreStillFillsTheScreen() {
        val remix = catalogue.remix("taffy_hue_sorbet")
        val offCentre = RotationCenter(0.72f, 0.31f)
        OffscreenRenderer(context, width = 320, height = 180).use { renderer ->
            // A fifth of the rotation period apart, so the sweep covers a full turn.
            for (step in 0 until 5) {
                val frame = renderer.render(
                    remix = remix,
                    rotationCenter = offCentre,
                    monotonicNanos = step * 48_000_000_000L,
                )
                val cleared = frame.fractionMatching(CLEAR_COLOR)
                Log.i("GyreVisual", "landscape offCentre step $step cleared=$cleared")
                assertTrue(
                    "background showed through on ${(cleared * 100).toInt()}% of the frame",
                    cleared < MAX_CLEARED_FRACTION,
                )
            }
        }
    }

    @Test
    fun filtersChangeTheSceneInTheExpectedDirection() {
        val remix = catalogue.remix("afterglow_hue_biolume")
        OffscreenRenderer(context).use { renderer ->
            val base = renderer.render(remix)
            val dimmed = renderer.render(remix, FilterState(dim = 0.5f))
            val grayscale = renderer.render(remix, FilterState(grayscale = 1f))
            val blurred = renderer.render(remix, FilterState(blur = 1f))

            val baseLuminance = base.meanLuminance()
            val dimmedLuminance = dimmed.meanLuminance()
            val baseChroma = base.meanChroma()
            val grayscaleChroma = grayscale.meanChroma()
            val baseEdges = base.edgeEnergy()
            val blurredEdges = blurred.edgeEnergy()
            Log.i(
                "GyreVisual",
                "filters: luminance=$baseLuminance->$dimmedLuminance " +
                    "chroma=$baseChroma->$grayscaleChroma edges=$baseEdges->$blurredEdges",
            )
            assertTrue(
                "dim luminance was $dimmedLuminance from $baseLuminance",
                dimmedLuminance < baseLuminance * 0.7f,
            )
            assertTrue(
                "grayscale chroma was $grayscaleChroma from $baseChroma",
                grayscaleChroma < baseChroma * 0.2f,
            )
            assertTrue(
                "blur edge energy was $blurredEdges from $baseEdges",
                blurredEdges < baseEdges,
            )
        }
    }

    @Test
    fun directAndBlurredPathsCoverEveryFilterCombinationAndCleanup() {
        val remix = catalogue.remix("planetarium_hue_golden")
        OffscreenRenderer(context, width = 72, height = 128).use { renderer ->
            listOf(0f, 1f).forEach { dim ->
                listOf(0f, 1f).forEach { grayscale ->
                    listOf(0f, 1f).forEach { blur ->
                        renderer.render(remix, FilterState(dim, grayscale, blur))
                    }
                }
            }
            assertEquals(2, renderer.debugSnapshot().framebufferCount)
            renderer.render(remix)
            assertEquals(0, renderer.debugSnapshot().framebufferCount)
            renderer.trimMemory()
            assertTrue(renderer.debugSnapshot().textureIdsByKey.isEmpty())
        }
    }

    /**
     * Resolved-colour variants share no image files, so a scene switch has nothing to carry
     * over — what the cache still owes is that re-rendering the same scene never re-decodes,
     * and that switching away evicts what the new scene does not use rather than accumulating
     * every scene ever shown.
     */
    @Test
    fun textureCacheReusesWithinASceneAndEvictsAcrossSwitches() {
        OffscreenRenderer(context, width = 72, height = 128).use { renderer ->
            renderer.render(catalogue.remix("planetarium_hue_golden"))
            val first = renderer.debugSnapshot().textureIdsByKey
            renderer.render(catalogue.remix("planetarium_hue_golden"))
            val again = renderer.debugSnapshot().textureIdsByKey
            assertEquals(first, again)

            renderer.render(catalogue.remix("orbitgarden_hue_citrus"))
            val switched = renderer.debugSnapshot().textureIdsByKey
            assertTrue((first.keys intersect switched.keys).isEmpty())
        }
    }

    @Test
    fun failedSceneSwitchKeepsCompletePreviousScene() {
        val working = catalogue.remix("planetarium_hue_golden")
        val broken = working.copy(
            id = "broken_scene",
            layers = working.layers.mapIndexed { index, layer ->
                if (index == working.layers.lastIndex) {
                    layer.copy(imageUrl = "assets/artwork/missing.webp")
                } else {
                    layer
                }
            },
        )
        OffscreenRenderer(context, width = 72, height = 128).use { renderer ->
            renderer.render(working)
            val before = renderer.debugSnapshot()
            try {
                renderer.render(broken)
                fail("Broken scene unexpectedly rendered")
            } catch (error: SceneRenderer.SceneLoadException) {
                assertTrue(error.assetPath.endsWith("missing.webp"))
            }
            val after = renderer.debugSnapshot()
            assertEquals(before.sceneId, after.sceneId)
            assertEquals(before.textureIdsByKey, after.textureIdsByKey)
            renderer.render(working)
        }
    }

    @Test
    fun rendererResourcesCanBeRecreatedWithAContext() {
        repeat(2) {
            OffscreenRenderer(context, width = 72, height = 128).use { renderer ->
                renderer.render(catalogue.remix("truchet_hue_circuit"))
            }
        }
    }

    /**
     * The reference rendering for [remix], from the test APK's own assets.
     *
     * These used to be the catalogue's `singleColumn` previews, but those are 96 MiB the app never
     * draws, so they stopped shipping. They are fixtures rather than content, so the four the
     * comparison needs live here instead — see `app/src/androidTest/assets/previews`.
     */
    private fun loadCenterCroppedPreview(remix: Remix, width: Int, height: Int): PixelFrame {
        val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
        val name = fixtures.list("previews").orEmpty().first { it.startsWith("${remix.id}.") }
        val bitmap = fixtures.open("previews/$name").use(BitmapFactory::decodeStream)
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        // A rotating scene is framed tighter so its edges never swing into view; crop the
        // reference by the same factor, or the comparison would only measure that framing.
        val coverage = sceneCoverage(remix, width.toFloat() / height)
        val cropWidth = (width / scale * coverage).toInt().coerceAtMost(bitmap.width)
        val cropHeight = (height / scale * coverage).toInt().coerceAtMost(bitmap.height)
        val cropped = Bitmap.createBitmap(
            bitmap,
            (bitmap.width - cropWidth) / 2,
            (bitmap.height - cropHeight) / 2,
            cropWidth,
            cropHeight,
        )
        val scaled = Bitmap.createScaledBitmap(cropped, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        return PixelFrame(width, height, pixels)
    }

    /** How much of the artwork the renderer keeps for [remix], via the renderer's own rule. */
    private fun sceneCoverage(remix: Remix, viewportAspect: Float): Float {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val layer = ArtworkPaths.resolve(remix.layers.first().imageUrl, context)
        layer.openStream(context).use { BitmapFactory.decodeStream(it, null, bounds) }
        val sceneAspect = bounds.outWidth.toFloat() / bounds.outHeight
        val window = SceneCoverage.window(remix, sceneAspect, viewportAspect, RotationCenter.Center)
        val unshrunk = if (sceneAspect > viewportAspect) 1f else sceneAspect / viewportAspect
        return window.y / unshrunk
    }

    private companion object {
        /** The colour the renderer clears to; `taffy_hue_sorbet` contains none of it. */
        const val CLEAR_COLOR = 0xFF000000.toInt()

        /** Room for a stray blended pixel, well below anything an exposed edge would produce. */
        const val MAX_CLEARED_FRACTION = 0.001f
    }

    private data class VisualLimit(
        val remixId: String,
        val maxMeanRgbError: Float,
        val minSsim: Float,
    )
}
