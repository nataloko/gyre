package dev.paperouette.wallpaper.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePacerTest {
    @Test
    fun deadlinesStayOnAnAbsoluteCadenceAfterLateFrames() {
        val pacer = FramePacer()

        assertEquals(FramePacer.INTERACTIVE_PERIOD_NANOS, pacer.nextDeadline(0L, true))
        assertEquals(
            FramePacer.INTERACTIVE_PERIOD_NANOS * 3,
            pacer.nextDeadline(FramePacer.INTERACTIVE_PERIOD_NANOS * 2, true),
        )
    }

    @Test
    fun cadenceChangesBetweenInteractionAndIdle() {
        val pacer = FramePacer()
        pacer.nextDeadline(1_000L, true)

        assertEquals(
            2_000L + FramePacer.IDLE_PERIOD_NANOS,
            pacer.nextDeadline(2_000L, false),
        )
    }

    @Test
    fun animationClockDoesNotJumpAcrossPauses() {
        val clock = AnimationClock()
        assertEquals(0L, clock.sample(1_000L, running = true))
        assertEquals(500L, clock.sample(1_500L, running = true))
        assertEquals(500L, clock.sample(9_000L, running = false))
        assertEquals(500L, clock.sample(20_000L, running = true))
        assertEquals(750L, clock.sample(20_250L, running = true))
    }

    @Test
    fun animationClockScalesWithSpeed() {
        val clock = AnimationClock()
        clock.sample(1_000L, running = true, speed = 2f)

        assertEquals(1_000L, clock.sample(1_500L, running = true, speed = 2f))
        assertEquals(1_250L, clock.sample(2_000L, running = true, speed = 0.5f))
    }

    @Test
    fun aStillSceneHoldsItsPlaceInTheTimeline() {
        val clock = AnimationClock()
        clock.sample(1_000L, running = true, speed = 1f)
        val reached = clock.sample(1_500L, running = true, speed = 1f)

        assertEquals(reached, clock.sample(9_000L, running = true, speed = 0f))
        assertEquals(reached, clock.sample(90_000L, running = true, speed = 0f))
    }

    /**
     * Held still, the host stops asking for frames, so the last tick can be minutes old. Resuming
     * rebases rather than handing all of that to the timeline at once — `EglRenderHost` calls this
     * whenever the speed changes for exactly that reason.
     */
    @Test
    fun rebasingAfterAStillStretchDoesNotJumpTheTimeline() {
        val clock = AnimationClock()
        clock.sample(1_000L, running = true, speed = 0f)
        val reached = clock.sample(600_000L, running = true, speed = 0f)

        clock.resetBaseline()

        assertEquals(reached, clock.sample(900_000L, running = true, speed = 1f))
        assertEquals(reached + 500L, clock.sample(900_500L, running = true, speed = 1f))
    }

    @Test
    fun animationClockIgnoresAnUnusableSpeed() {
        val clock = AnimationClock()
        clock.sample(1_000L, running = true, speed = Float.NaN)

        assertEquals(500L, clock.sample(1_500L, running = true, speed = Float.NaN))
        // Above the top of the fader's range, and below the bottom.
        assertEquals(500L + 3_000L, clock.sample(2_500L, running = true, speed = 99f))
        assertEquals(3_500L, clock.sample(3_500L, running = true, speed = -1f))
    }
}
