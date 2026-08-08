package dev.gyre.wallpaper.motion

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gyre.wallpaper.data.GyreSettings
import dev.gyre.wallpaper.render.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The gesture recogniser, driven the way a hand drives it.
 *
 * `MotionMath` and the trackers beside it are covered by JVM tests, but the state machine that
 * turns a stream of pointers into a pan, a nudge or a change of artwork is the part with the
 * corners in it: centroids that rebase as fingers leave, a tap told from a drag by slop, and the
 * rule that a multi-finger gesture must not also pan. Those need real `MotionEvent`s, so they run
 * on a device.
 */
@RunWith(AndroidJUnit4::class)
class MotionControllerTouchTest {
    private lateinit var context: Context
    private lateinit var controller: MotionController
    private var slop = 0f

    /** Reported remixes and designs, so a gesture that fires twice is visible as two entries. */
    private val nextRemixCalls = mutableListOf<Unit>()
    private val nextDesignCalls = mutableListOf<Unit>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = MotionController(context)
        slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        nextRemixCalls.clear()
        nextDesignCalls.clear()
    }

    @Test
    fun oneFingerDragPansTheScene() {
        down(CENTRE_X, CENTRE_Y)
        move(CENTRE_X + SURFACE / 4f, CENTRE_Y)
        up(CENTRE_X + SURFACE / 4f, CENTRE_Y)

        val state = controller.snapshot(1L).state
        // A quarter of the shorter edge, and the shorter edge is a whole unit of pan.
        assertEquals(0.25f, state.dragX, 0.01f)
        assertEquals(0f, state.dragY, 0.01f)
    }

    /** Screen coordinates measure down, pan measures up, so a downward drag is a negative pan. */
    @Test
    fun draggingDownPansTheOtherWay() {
        down(CENTRE_X, CENTRE_Y)
        move(CENTRE_X, CENTRE_Y + SURFACE / 4f)
        up(CENTRE_X, CENTRE_Y + SURFACE / 4f)

        assertEquals(-0.25f, controller.snapshot(1L).state.dragY, 0.01f)
    }

    @Test
    fun panningStopsAtTheEdgeOfItsRange() {
        down(CENTRE_X, CENTRE_Y)
        // Far past a whole surface, several times over.
        repeat(6) { step -> move(CENTRE_X + SURFACE * (step + 1), CENTRE_Y) }
        up(CENTRE_X + SURFACE * 6f, CENTRE_Y)

        assertEquals(1f, controller.snapshot(1L).state.dragX, 0.0001f)
    }

    /**
     * The rule that keeps a two-finger tap from also shoving the artwork sideways: once a gesture
     * has been multi-touch, dragging is off for the rest of it — including after a finger lifts.
     */
    @Test
    fun aMultiFingerGestureNeverPans() {
        down(CENTRE_X, CENTRE_Y)
        pointerDown(listOf(CENTRE_X to CENTRE_Y, CENTRE_X + 200f to CENTRE_Y))
        multiMove(
            listOf(
                CENTRE_X + SURFACE / 3f to CENTRE_Y,
                CENTRE_X + 200f + SURFACE / 3f to CENTRE_Y,
            ),
        )
        pointerUp(listOf(CENTRE_X + SURFACE / 3f to CENTRE_Y, CENTRE_X + 200f to CENTRE_Y), 1)
        move(CENTRE_X + SURFACE / 2f, CENTRE_Y)
        up(CENTRE_X + SURFACE / 2f, CENTRE_Y)

        val state = controller.snapshot(1L).state
        assertEquals(0f, state.dragX, 0.0001f)
        assertEquals(0f, state.dragY, 0.0001f)
    }

    @Test
    fun twoFingerTapAsksForTheNextVariantOnce() {
        down(CENTRE_X, CENTRE_Y)
        pointerDown(listOf(CENTRE_X to CENTRE_Y, CENTRE_X + 120f to CENTRE_Y))
        pointerUp(listOf(CENTRE_X to CENTRE_Y, CENTRE_X + 120f to CENTRE_Y), 1)
        up(CENTRE_X, CENTRE_Y)

        assertEquals(1, nextRemixCalls.size)
        assertEquals(0, nextDesignCalls.size)
    }

    @Test
    fun threeFingerTapAsksForTheNextPieceOnce() {
        down(CENTRE_X, CENTRE_Y)
        pointerDown(listOf(CENTRE_X to CENTRE_Y, CENTRE_X + 120f to CENTRE_Y))
        pointerDown(
            listOf(
                CENTRE_X to CENTRE_Y,
                CENTRE_X + 120f to CENTRE_Y,
                CENTRE_X + 240f to CENTRE_Y,
            ),
        )
        pointerUp(
            listOf(
                CENTRE_X to CENTRE_Y,
                CENTRE_X + 120f to CENTRE_Y,
                CENTRE_X + 240f to CENTRE_Y,
            ),
            2,
        )
        pointerUp(listOf(CENTRE_X to CENTRE_Y, CENTRE_X + 120f to CENTRE_Y), 1)
        up(CENTRE_X, CENTRE_Y)

        assertEquals(1, nextDesignCalls.size)
        assertEquals(0, nextRemixCalls.size)
    }

    /** A gesture that travelled is a drag, so lifting it must not also count as a tap. */
    @Test
    fun aTwoFingerDragDoesNotChangeTheArtwork() {
        down(CENTRE_X, CENTRE_Y)
        pointerDown(listOf(CENTRE_X to CENTRE_Y, CENTRE_X + 120f to CENTRE_Y))
        multiMove(
            listOf(
                CENTRE_X + SURFACE / 3f to CENTRE_Y,
                CENTRE_X + 120f + SURFACE / 3f to CENTRE_Y,
            ),
        )
        pointerUp(
            listOf(CENTRE_X + SURFACE / 3f to CENTRE_Y, CENTRE_X + 120f to CENTRE_Y),
            1,
        )
        up(CENTRE_X + SURFACE / 3f, CENTRE_Y)

        assertEquals(0, nextRemixCalls.size)
        assertEquals(0, nextDesignCalls.size)
    }

    @Test
    fun nudgeSpinsOnASingleTapOnlyWhenItIsTurnedOn() {
        down(CENTRE_X, CENTRE_Y)
        up(CENTRE_X, CENTRE_Y)
        controller.snapshot(0L)
        assertEquals(0f, controller.snapshot(HALF_SECOND).state.spinRadians, 0.0001f)

        val settings = GyreSettings(tapToSpin = true)
        down(CENTRE_X, CENTRE_Y, settings)
        up(CENTRE_X, CENTRE_Y, settings)
        controller.snapshot(HALF_SECOND)

        assertNotEquals(0f, controller.snapshot(HALF_SECOND * 2).state.spinRadians)
    }

    /**
     * Held still, nothing reaches the renderer — from any of the six ways in.
     *
     * The value of the switch is not only that the artwork stops. It reports no motion either, so
     * the render host stops asking for frames at all; a leak from any one input would quietly put
     * that back and the wallpaper would cost a redraw a frame for nothing visible.
     */
    @Test
    fun heldStillNothingMoves() {
        val settings = GyreSettings(
            stillArtwork = true,
            tapToSpin = true,
            tiltEnabled = true,
            flickEnabled = true,
        )
        controller.updateSettings(settings)

        // Every input that can move the scene, all at once.
        controller.setLauncherOffsets(1f, 1f)
        controller.setLauncherZoom(1f)
        down(CENTRE_X, CENTRE_Y, settings)
        move(CENTRE_X + SURFACE / 4f, CENTRE_Y, settings)
        up(CENTRE_X + SURFACE / 4f, CENTRE_Y, settings)
        down(CENTRE_X, CENTRE_Y, settings)
        up(CENTRE_X, CENTRE_Y, settings)

        controller.snapshot(0L)
        val sample = controller.snapshot(HALF_SECOND)

        assertEquals(MotionState(), sample.state)
        assertFalse("a still wallpaper must not keep asking for frames", sample.highMotion)
    }

    /** Turning it back on starts from rest, rather than resuming whatever was coasting. */
    @Test
    fun comingOffHoldStillDoesNotResumeMidFlight() {
        val moving = GyreSettings(tapToSpin = true)
        down(CENTRE_X, CENTRE_Y, moving)
        up(CENTRE_X, CENTRE_Y, moving)
        controller.snapshot(0L)

        controller.updateSettings(GyreSettings(stillArtwork = true, tapToSpin = true))
        controller.snapshot(HALF_SECOND)
        controller.updateSettings(moving)

        val resumed = controller.snapshot(HALF_SECOND * 2).state
        assertEquals(0f, resumed.spinRadians, 0.0001f)
        assertEquals(0f, resumed.dragX, 0.0001f)
    }

    /**
     * A tap is the one input with no direction of its own, so it takes the scene's.
     *
     * A flick or a drag carries the hand's direction and keeps it whatever else is set. A tap has
     * nothing to keep, and took a fixed sign — so a nudge went the same way round with Reverse on
     * as with it off, and the setting looked broken to anyone who tested it by nudging.
     */
    @Test
    fun aNudgeFollowsReverse() {
        fun nudge(reversed: Boolean): Float {
            // A fresh controller each way, so neither reading carries the other's spin.
            controller = MotionController(context)
            val settings = GyreSettings(tapToSpin = true, rotationReversed = reversed)
            down(CENTRE_X, CENTRE_Y, settings)
            up(CENTRE_X, CENTRE_Y, settings)
            controller.snapshot(HALF_SECOND)
            return controller.snapshot(HALF_SECOND * 2).state.spinRadians
        }

        val forward = nudge(reversed = false)
        val reversed = nudge(reversed = true)

        assertTrue("a nudge should spin the artwork at all", abs(forward) > 0f)
        assertTrue(
            "a nudge should follow Reverse: forward $forward, reversed $reversed",
            forward * reversed < 0f,
        )
    }

    @Test
    fun aTapThatTravelledIsNotANudge() {
        val settings = GyreSettings(tapToSpin = true)
        down(CENTRE_X, CENTRE_Y, settings)
        move(CENTRE_X + slop * 4f, CENTRE_Y, settings)
        up(CENTRE_X + slop * 4f, CENTRE_Y, settings)
        controller.snapshot(0L)

        // It panned instead, and panning is not a spin.
        val state = controller.snapshot(HALF_SECOND).state
        assertEquals(0f, state.spinRadians, 0.0001f)
        assertTrue("a travelled touch should still pan", abs(state.dragX) > 0f)
    }

    /**
     * Letting go with speed hands the pan over to inertia, which then decays. Cancelling instead —
     * the system taking the gesture away — must not.
     */
    @Test
    fun aCancelledGestureCoastsNowhere() {
        down(CENTRE_X, CENTRE_Y)
        move(CENTRE_X + SURFACE / 4f, CENTRE_Y)
        cancel(CENTRE_X + SURFACE / 4f, CENTRE_Y)

        controller.snapshot(0L)
        val settled = controller.snapshot(HALF_SECOND).state.dragX

        assertEquals(0.25f, settled, 0.01f)
        assertEquals(settled, controller.snapshot(HALF_SECOND * 4).state.dragX, 0.0001f)
    }

    @Test
    fun theLauncherZoomIsHeldUntilTheNextFrame() {
        controller.updateSettings(GyreSettings(launcherZoomEnabled = true))
        controller.setLauncherZoom(0.75f)
        assertEquals(0.75f, controller.snapshot(1L).state.launcherZoom, 0.0001f)

        // Out of range or unusable, and it must still hand the shader something it can use.
        controller.setLauncherZoom(4f)
        assertEquals(1f, controller.snapshot(2L).state.launcherZoom, 0.0001f)
        controller.setLauncherZoom(Float.NaN)
        assertEquals(0f, controller.snapshot(3L).state.launcherZoom, 0.0001f)
    }

    @Test
    fun turningTheLauncherZoomOffLetsTheArtworkBack() {
        controller.updateSettings(GyreSettings(launcherZoomEnabled = true))
        controller.setLauncherZoom(1f)

        controller.updateSettings(GyreSettings(launcherZoomEnabled = false))

        assertEquals(0f, controller.snapshot(1L).state.launcherZoom, 0.0001f)
        // And it stays put while it is off, however far the launcher zooms.
        controller.setLauncherZoom(1f)
        assertEquals(0f, controller.snapshot(2L).state.launcherZoom, 0.0001f)
    }

    // --- Event plumbing -------------------------------------------------------------------------

    private fun dispatch(event: MotionEvent, settings: GyreSettings) {
        try {
            controller.onTouchEvent(
                event = event,
                width = SURFACE.toInt(),
                height = SURFACE.toInt(),
                currentSettings = settings,
                onNextRemix = { nextRemixCalls += Unit },
                onNextDesign = { nextDesignCalls += Unit },
            )
        } finally {
            event.recycle()
        }
    }

    private fun down(x: Float, y: Float, settings: GyreSettings = GyreSettings()) =
        dispatch(single(MotionEvent.ACTION_DOWN, x, y), settings)

    private fun move(x: Float, y: Float, settings: GyreSettings = GyreSettings()) =
        dispatch(single(MotionEvent.ACTION_MOVE, x, y), settings)

    private fun up(x: Float, y: Float, settings: GyreSettings = GyreSettings()) =
        dispatch(single(MotionEvent.ACTION_UP, x, y), settings)

    private fun cancel(x: Float, y: Float, settings: GyreSettings = GyreSettings()) =
        dispatch(single(MotionEvent.ACTION_CANCEL, x, y), settings)

    private fun pointerDown(points: List<Pair<Float, Float>>) = dispatch(
        multi(MotionEvent.ACTION_POINTER_DOWN, points, points.lastIndex),
        GyreSettings(),
    )

    private fun pointerUp(points: List<Pair<Float, Float>>, index: Int) =
        dispatch(multi(MotionEvent.ACTION_POINTER_UP, points, index), GyreSettings())

    private fun multiMove(points: List<Pair<Float, Float>>) =
        dispatch(multi(MotionEvent.ACTION_MOVE, points, 0), GyreSettings())

    private fun single(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(DOWN_TIME, DOWN_TIME, action, x, y, 0)

    private fun multi(action: Int, points: List<Pair<Float, Float>>, index: Int): MotionEvent {
        val properties = Array(points.size) { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(points.size) { pointer ->
            MotionEvent.PointerCoords().apply {
                x = points[pointer].first
                y = points[pointer].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            DOWN_TIME,
            DOWN_TIME,
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            points.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
    }

    private companion object {
        /** A square surface, so a unit of pan is the same distance on both axes. */
        const val SURFACE = 1000f
        const val CENTRE_X = SURFACE / 2f
        const val CENTRE_Y = SURFACE / 2f
        const val DOWN_TIME = 1_000L
        const val HALF_SECOND = 500_000_000L
    }
}
