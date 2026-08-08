package dev.paperouette.wallpaper.motion

import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionControllerTest {
    @Test
    fun dragStopsAtItsBoundary() {
        val result = MotionMath.advanceBounded(position = 0.9f, velocity = 2f, deltaSeconds = 1f)

        assertEquals(1f, result.position, 0f)
        assertEquals(0f, result.velocity, 0f)
    }

    @Test
    fun moderateSpinIsNotWrapped() {
        assertEquals(
            (PI * 2.5).toFloat(),
            MotionMath.wrapSpinRadians((PI * 2.5).toFloat()),
            0.0001f,
        )
    }

    @Test
    fun spinWrapKeepsScaledSceneRotationContinuous() {
        val period = (MotionMath.SPIN_WRAP_TURNS * 2.0 * PI).toFloat()
        val overflowing = period / 2f + 0.25f
        val wrapped = MotionMath.wrapSpinRadians(overflowing)

        assertEquals(overflowing - period, wrapped, 0.0001f)
        // The catalogue's 0.75 scaler must see the wrap as a whole number of turns.
        val appliedJump = (overflowing - wrapped) * 0.75f
        val twoPi = (PI * 2.0).toFloat()
        val remainder = appliedJump.mod(twoPi)
        assertTrue(minOf(remainder, twoPi - remainder) < 0.001f)
    }

    @Test
    fun displayRotationSelectsMatchingSensorAxes() {
        assertEquals(
            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X,
            DisplayRotationRemapper.axes(Surface.ROTATION_90),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X,
            DisplayRotationRemapper.axes(Surface.ROTATION_270),
        )
    }

    @Test
    fun meaningfulMotionExpiresAfterHalfASecond() {
        val tracker = MotionActivityTracker()
        tracker.markMeaningfulMotion(1_000_000_000L)

        assertTrue(tracker.isHighMotion(1_500_000_000L, false, 0f, 0f, 0f))
        assertFalse(tracker.isHighMotion(1_500_000_001L, false, 0f, 0f, 0f))
        assertTrue(tracker.isHighMotion(2_000_000_000L, true, 0f, 0f, 0f))
    }
}
