package dev.gyre.wallpaper.render

import dev.gyre.wallpaper.model.Layer
import dev.gyre.wallpaper.model.RotationDirection
import dev.gyre.wallpaper.model.RotationSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class LayerTransformTest {
    @Test
    fun clockwiseRotationUsesMonotonicCataloguePeriod() {
        val layer = layer(RotationSpec(120f, RotationDirection.CLOCKWISE))

        assertEquals(
            (PI / 2.0).toFloat(),
            LayerTransform.rotationRadians(layer, 30_000_000_000L),
            0.0001f,
        )
    }

    @Test
    fun anticlockwiseRotationIsNegative() {
        val layer = layer(RotationSpec(800f, RotationDirection.ANTICLOCKWISE))

        assertEquals(
            (-PI).toFloat(),
            LayerTransform.rotationRadians(layer, 400_000_000_000L),
            0.0001f,
        )
    }

    @Test
    fun reversingTurnsTheSceneTheOtherWayWhicheverWayItRan() {
        val clockwise = layer(RotationSpec(120f, RotationDirection.CLOCKWISE))
        val anticlockwise = layer(RotationSpec(120f, RotationDirection.ANTICLOCKWISE))

        assertEquals(
            LayerTransform.rotationRadians(clockwise, 30_000_000_000L),
            -LayerTransform.rotationRadians(clockwise, 30_000_000_000L, reversed = true),
            0.0001f,
        )
        assertEquals(
            LayerTransform.rotationRadians(clockwise, 30_000_000_000L),
            LayerTransform.rotationRadians(anticlockwise, 30_000_000_000L, reversed = true),
            0.0001f,
        )
    }

    /**
     * Reversing is about the scene's own animation. The user's spin keeps its sign, so a flick or a
     * drag still pushes the artwork the way the hand went.
     */
    @Test
    fun reversingLeavesTheUsersOwnSpinAlone() {
        val layer = layer(RotationSpec(120f, RotationDirection.CLOCKWISE))
        val animation = LayerTransform.rotationRadians(layer, 30_000_000_000L)

        assertEquals(
            -animation + 3f * 0.75f,
            LayerTransform.totalRotationRadians(layer, 30_000_000_000L, 3f, 0.75f, reversed = true),
            0.0001f,
        )
    }

    @Test
    fun tiltOnlyLayerIgnoresLauncherAndDrag() {
        val layer = layer().copy(parallaxOnlyOnTilt = true, parallaxScale = 0.4f)
        val motion = MotionState(
            launcherX = 1f,
            launcherY = 1f,
            dragX = 1f,
            dragY = 1f,
            tiltX = 0.5f,
            tiltY = -0.5f,
        )

        val (x, y) = LayerTransform.parallax(layer, motion)

        assertEquals(0.016f, x, 0.0001f)
        assertEquals(-0.016f, y, 0.0001f)
    }

    @Test
    fun combinedParallaxInputIsBounded() {
        val layer = layer().copy(parallaxScale = 0.4f)
        val motion = MotionState(
            launcherX = 1f,
            dragX = 1f,
            tiltX = 2f,
            launcherY = -1f,
            dragY = -1f,
            tiltY = -2f,
        )

        val (x, y) = LayerTransform.parallax(layer, motion)

        assertEquals(0.032f, x, 0.0001f)
        assertEquals(-0.032f, y, 0.0001f)
        assertEquals(0.032f, LayerTransform.maxParallaxOffset(layer), 0.0001f)
    }

    @Test
    fun spinLeavesLayersTheCatalogueDoesNotRotateAlone() {
        val backdrop = layer()
        val spinner = layer(RotationSpec(120f, RotationDirection.CLOCKWISE))

        assertEquals(
            0f,
            LayerTransform.totalRotationRadians(backdrop, 30_000_000_000L, 3f, 0.75f),
            0.0001f,
        )
        assertEquals(
            (PI / 2.0).toFloat() + 3f * 0.75f,
            LayerTransform.totalRotationRadians(spinner, 30_000_000_000L, 3f, 0.75f),
            0.0001f,
        )
    }

    @Test
    fun anUnusableRotationCentreFallsBackToTheMiddle() {
        assertEquals(0.5f, RotationCenter(Float.NaN, 0.25f).sanitized().x, 0.0001f)
        assertEquals(0.25f, RotationCenter(Float.NaN, 0.25f).sanitized().y, 0.0001f)
        assertEquals(1f, RotationCenter(4f, -2f).sanitized().x, 0.0001f)
        assertEquals(0f, RotationCenter(4f, -2f).sanitized().y, 0.0001f)
    }

    @Test
    fun filterValuesAreClamped() {
        assertEquals(
            FilterState(dim = 1f, grayscale = 0f, blur = 0.5f),
            FilterState(dim = 2f, grayscale = -1f, blur = 0.5f).normalized(),
        )
    }

    private fun layer(rotation: RotationSpec? = null) = Layer(
        imageUrl = "assets/artwork/test.webp",
        type = "animated",
        rotation = rotation,
    )
}

