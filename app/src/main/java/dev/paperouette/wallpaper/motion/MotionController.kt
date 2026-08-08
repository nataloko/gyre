package dev.paperouette.wallpaper.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.VelocityTracker
import android.view.ViewConfiguration
import dev.paperouette.wallpaper.data.PaperouetteSettings
import dev.paperouette.wallpaper.render.MotionSample
import dev.paperouette.wallpaper.render.MotionState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

internal data class BoundedAxis(val position: Float, val velocity: Float)

internal object MotionMath {
    fun advanceBounded(position: Float, velocity: Float, deltaSeconds: Float): BoundedAxis {
        val next = position + velocity * deltaSeconds
        val bounded = next.coerceIn(-MAX_DISPLACEMENT, MAX_DISPLACEMENT)
        val outward = (bounded >= MAX_DISPLACEMENT && velocity > 0f) ||
            (bounded <= -MAX_DISPLACEMENT && velocity < 0f)
        return BoundedAxis(bounded, if (outward) 0f else velocity)
    }

    fun wrapSpinRadians(radians: Float): Float {
        val half = SPIN_WRAP_PERIOD_RADIANS / 2f
        return ((radians + half) % SPIN_WRAP_PERIOD_RADIANS + SPIN_WRAP_PERIOD_RADIANS) %
            SPIN_WRAP_PERIOD_RADIANS - half
    }

    /**
     * Scenes rotate by spin times their inputRotationScaler, so a wrap is only invisible while
     * SPIN_WRAP_TURNS times the scaler is a whole number of turns. BundledCatalogRepository
     * checks every remix against this constant.
     */
    const val SPIN_WRAP_TURNS = 4

    private val SPIN_WRAP_PERIOD_RADIANS = (SPIN_WRAP_TURNS * 2.0 * PI).toFloat()
    private const val MAX_DISPLACEMENT = 1f
}

internal class MotionActivityTracker {
    private var lastMeaningfulMotionNanos = Long.MIN_VALUE

    fun markMeaningfulMotion(monotonicNanos: Long) {
        lastMeaningfulMotionNanos = monotonicNanos
    }

    fun isHighMotion(
        monotonicNanos: Long,
        touching: Boolean,
        dragVelocityX: Float,
        dragVelocityY: Float,
        spinVelocity: Float,
    ): Boolean = touching ||
        hypot(dragVelocityX, dragVelocityY) > VELOCITY_EPSILON ||
        abs(spinVelocity) > VELOCITY_EPSILON ||
        monotonicNanos - lastMeaningfulMotionNanos in 0..ACTIVITY_HOLD_NANOS

    companion object {
        const val ACTIVITY_HOLD_NANOS = 500_000_000L
        private const val VELOCITY_EPSILON = 0.01f
    }
}

internal object DisplayRotationRemapper {
    fun axes(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
}

internal class MotionController(private val context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAcceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeoutMillis = ViewConfiguration.getTapTimeout().toLong()
    private val activity = MotionActivityTracker()

    private var active = false
    private var touching = false
    private var launcherX = 0f
    private var launcherY = 0f
    private var launcherZoom = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var dragVelocityX = 0f
    private var dragVelocityY = 0f
    private var tiltX = 0f
    private var tiltY = 0f
    private var spin = 0f
    private var spinVelocity = 0f
    private var lastFrameNanos = 0L
    private var lastFlickNanos = 0L
    private var settings = PaperouetteSettings()
    private var frameRequester: () -> Unit = {}

    private var velocityTracker: VelocityTracker? = null
    private var touchStartNanos = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startCentroidX = 0f
    private var startCentroidY = 0f
    private var maxTouchDistance = 0f
    private var maxPointerCount = 0

    fun setFrameRequester(requester: () -> Unit) {
        synchronized(this) { frameRequester = requester }
    }

    @Synchronized
    fun start(currentSettings: PaperouetteSettings) {
        settings = currentSettings
        lastFrameNanos = 0L
        if (active) return
        active = true
        // Held still, nothing is listened for at all. Reporting rest would have been enough to
        // stop the drawing, but the sensors are the larger share of what a live wallpaper costs:
        // leaving the rotation vector, gyroscope and accelerometer registered at GAME rate to
        // deliver readings that are then thrown away is most of the power with none of the effect.
        if (settings.stillArtwork) return
        if (settings.tiltEnabled) {
            rotationVector?.also { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        if (settings.flickEnabled) {
            gyroscope?.also { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            linearAcceleration?.also {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    @Synchronized
    fun updateSettings(currentSettings: PaperouetteSettings) {
        val needsRestart = active && (
            settings.tiltEnabled != currentSettings.tiltEnabled ||
                settings.flickEnabled != currentSettings.flickEnabled ||
                settings.stillArtwork != currentSettings.stillArtwork
            )
        if (currentSettings.stillArtwork && !settings.stillArtwork) comeToRest()
        settings = currentSettings
        if (needsRestart) {
            sensors.unregisterListener(this)
            active = false
            start(currentSettings)
        }
        if (!currentSettings.tiltEnabled) {
            tiltX = 0f
            tiltY = 0f
        }
        if (!currentSettings.launcherZoomEnabled) launcherZoom = 0f
    }

    /**
     * Clears everything that had built up, so nothing is left mid-flight.
     *
     * Coming to rest has to discard the inertia as well as the positions. Keeping them would mean
     * a drag let go of just before the switch was thrown carried on coasting the moment it was
     * thrown back, minutes later, which reads as the artwork moving on its own.
     */
    private fun comeToRest() {
        launcherX = 0f
        launcherY = 0f
        launcherZoom = 0f
        dragX = 0f
        dragY = 0f
        dragVelocityX = 0f
        dragVelocityY = 0f
        tiltX = 0f
        tiltY = 0f
        spin = 0f
        spinVelocity = 0f
    }

    @Synchronized
    fun stop() {
        if (active) sensors.unregisterListener(this)
        active = false
        touching = false
        lastFrameNanos = 0L
        velocityTracker?.recycle()
        velocityTracker = null
    }

    @Synchronized
    fun setLauncherOffsets(xOffset: Float, yOffset: Float) {
        if (settings.stillArtwork) return
        val nextX = (xOffset - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val nextY = (yOffset - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        if (hypot(nextX - launcherX, nextY - launcherY) >= MEANINGFUL_MOTION_DELTA) {
            activity.markMeaningfulMotion(System.nanoTime())
        }
        launcherX = nextX
        launcherY = nextY
        frameRequester()
    }

    /**
     * Takes the launcher's zoom, 0 on the home screen and 1 at the app drawer.
     *
     * It goes through here rather than through the render state for the pacing: the window manager
     * delivers these as fast as it animates, and a render state pushed per callback would post a
     * frame each time, outrunning [FramePacer] while the surface was still asking the display for
     * 30. Held here, the next frame reads whatever the latest value is, and a moving zoom counts as
     * motion so the pacer runs at 60 while it lasts.
     */
    @Synchronized
    fun setLauncherZoom(zoom: Float) {
        if (settings.stillArtwork || !settings.launcherZoomEnabled) return
        val next = zoom.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        if (abs(next - launcherZoom) >= MEANINGFUL_MOTION_DELTA) {
            activity.markMeaningfulMotion(System.nanoTime())
        }
        launcherZoom = next
        frameRequester()
    }

    @Synchronized
    fun snapshot(monotonicNanos: Long = System.nanoTime()): MotionSample {
        // Held still, the controller reports rest and says so, which is what lets the render host
        // stop asking for frames rather than redraw an identical one thirty times a second. It is
        // gated here rather than at each input because there are six of them — tilt, flick, nudge,
        // drag, the launcher's pan and its zoom — and one of them would eventually be forgotten.
        if (settings.stillArtwork) {
            lastFrameNanos = monotonicNanos
            return MotionSample(state = MotionState(), highMotion = false)
        }
        if (lastFrameNanos == 0L) lastFrameNanos = monotonicNanos
        val delta = ((monotonicNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = monotonicNanos

        if (settings.touchInertiaSeconds > 0f) {
            val nextX = MotionMath.advanceBounded(dragX, dragVelocityX, delta)
            val nextY = MotionMath.advanceBounded(dragY, dragVelocityY, delta)
            dragX = nextX.position
            dragY = nextY.position
            val decay = exp(-4f * delta / settings.touchInertiaSeconds)
            dragVelocityX = nextX.velocity * decay
            dragVelocityY = nextY.velocity * decay
        } else {
            dragVelocityX = 0f
            dragVelocityY = 0f
        }
        spin = MotionMath.wrapSpinRadians(spin + spinVelocity * delta)
        spinVelocity *= exp(-2.2f * delta)

        return MotionSample(
            state = MotionState(
                launcherX = launcherX,
                launcherY = launcherY,
                launcherZoom = launcherZoom,
                dragX = dragX,
                dragY = dragY,
                tiltX = tiltX,
                tiltY = tiltY,
                spinRadians = spin,
            ),
            highMotion = activity.isHighMotion(
                monotonicNanos,
                touching,
                dragVelocityX,
                dragVelocityY,
                spinVelocity,
            ),
        )
    }

    fun onTouchEvent(
        event: MotionEvent,
        width: Int,
        height: Int,
        currentSettings: PaperouetteSettings,
        onNextRemix: () -> Unit,
        onNextDesign: () -> Unit,
    ): Boolean = synchronized(this) {
        settings = currentSettings
        val scale = max(1, min(width, height)).toFloat()
        val now = System.nanoTime()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                touching = true
                touchStartNanos = now
                maxPointerCount = 1
                lastTouchX = event.x
                lastTouchY = event.y
                startCentroidX = event.x
                startCentroidY = event.y
                maxTouchDistance = 0f
                dragVelocityX = 0f
                dragVelocityY = 0f
                activity.markMeaningfulMotion(now)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                velocityTracker?.addMovement(event)
                maxPointerCount = max(maxPointerCount, event.pointerCount)
                startCentroidX = (0 until event.pointerCount)
                    .sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount
                startCentroidY = (0 until event.pointerCount)
                    .sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount
                lastTouchX = startCentroidX
                lastTouchY = startCentroidY
                maxTouchDistance = 0f
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Rebase the centroid on the remaining pointers so a later move sees no jump.
                // Dragging stays disabled for the rest of a multi-finger gesture; that keeps
                // two- and three-finger taps from also panning the scene.
                velocityTracker?.addMovement(event)
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                lastTouchX = remaining.sumOf { event.getX(it).toDouble() }.toFloat() / remaining.size
                lastTouchY = remaining.sumOf { event.getY(it).toDouble() }.toFloat() / remaining.size
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val centroidX = (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }
                    .toFloat() / event.pointerCount
                val centroidY = (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }
                    .toFloat() / event.pointerCount
                maxTouchDistance = max(
                    maxTouchDistance,
                    hypot(centroidX - startCentroidX, centroidY - startCentroidY),
                )
                if (maxPointerCount == 1) {
                    dragX = (dragX + (centroidX - lastTouchX) / scale *
                        currentSettings.spinSensitivity).coerceIn(-1f, 1f)
                    dragY = (dragY - (centroidY - lastTouchY) / scale *
                        currentSettings.spinSensitivity).coerceIn(-1f, 1f)
                }
                lastTouchX = centroidX
                lastTouchY = centroidY
                activity.markMeaningfulMotion(now)
            }

            MotionEvent.ACTION_UP -> {
                touching = false
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000)
                    if (maxPointerCount == 1 && maxTouchDistance >= touchSlop) {
                        dragVelocityX = xVelocity / scale * currentSettings.spinSensitivity
                        dragVelocityY = -yVelocity / scale * currentSettings.spinSensitivity
                        if (dragX >= 1f && dragVelocityX > 0f) dragVelocityX = 0f
                        if (dragX <= -1f && dragVelocityX < 0f) dragVelocityX = 0f
                        if (dragY >= 1f && dragVelocityY > 0f) dragVelocityY = 0f
                        if (dragY <= -1f && dragVelocityY < 0f) dragVelocityY = 0f
                    }
                    recycle()
                }
                velocityTracker = null
                val elapsedMs = (now - touchStartNanos) / 1_000_000
                if (elapsedMs <= tapTimeoutMillis && maxTouchDistance < touchSlop) {
                    when (maxPointerCount) {
                        1 -> if (currentSettings.tapToSpin) {
                            // A tap is the one input with no direction of its own. A flick or a
                            // drag carries the hand's, which is why the spin they produce keeps
                            // its sign whatever else is set — but a tap has nothing to keep, and
                            // taking the fixed sign meant a nudge went the same way round with
                            // Reverse on as with it off, which is the setting visibly not working.
                            //
                            // Mirror is deliberately not consulted. It reflects the mapping, so it
                            // already turns the scene's animation and the user's spin together;
                            // correcting for it here would only set the two against each other.
                            spinVelocity += TAP_SPIN_RADIANS_PER_SECOND *
                                currentSettings.spinSensitivity *
                                if (currentSettings.rotationReversed) -1f else 1f
                        }
                        2 -> onNextRemix()
                        3 -> onNextDesign()
                    }
                }
                activity.markMeaningfulMotion(now)
            }

            MotionEvent.ACTION_CANCEL -> {
                touching = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        frameRequester()
        true
    }

    override fun onSensorChanged(event: SensorEvent) = synchronized(this) {
        val now = System.nanoTime()
        var requestImmediately = false
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> if (settings.tiltEnabled) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val displayRotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                    ?: Surface.ROTATION_0
                val (xAxis, yAxis) = DisplayRotationRemapper.axes(displayRotation)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    xAxis,
                    yAxis,
                    remappedRotationMatrix,
                )
                SensorManager.getOrientation(remappedRotationMatrix, orientation)
                val rawX = (orientation[2] / MAX_TILT_RADIANS)
                    .coerceIn(-1f, 1f) * settings.tiltSensitivity
                val rawY = (orientation[1] / MAX_TILT_RADIANS)
                    .coerceIn(-1f, 1f) * settings.tiltSensitivity
                val nextX = tiltX + (rawX - tiltX) * SENSOR_LOW_PASS_ALPHA
                val nextY = tiltY + (rawY - tiltY) * SENSOR_LOW_PASS_ALPHA
                if (hypot(nextX - tiltX, nextY - tiltY) >= MEANINGFUL_MOTION_DELTA) {
                    activity.markMeaningfulMotion(now)
                    requestImmediately = true
                }
                tiltX = nextX
                tiltY = nextY
            }

            Sensor.TYPE_GYROSCOPE -> if (settings.flickEnabled) {
                val magnitude = hypot(event.values[0], event.values[1])
                requestImmediately = detectFlick(
                    magnitude,
                    event.values[1] + event.values[0],
                    GYRO_THRESHOLD,
                    now,
                )
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> if (settings.flickEnabled) {
                val magnitude = hypot(event.values[0], event.values[1])
                requestImmediately = detectFlick(
                    magnitude,
                    event.values[0] - event.values[1],
                    ACCELERATION_THRESHOLD,
                    now,
                )
            }
        }
        if (requestImmediately) frameRequester()
    }

    private fun detectFlick(
        magnitude: Float,
        direction: Float,
        threshold: Float,
        monotonicNanos: Long,
    ): Boolean {
        val scaledThreshold = threshold / settings.flickSensitivity.coerceAtLeast(0.25f)
        if (magnitude < scaledThreshold ||
            monotonicNanos - lastFlickNanos < FLICK_COOLDOWN_NANOS
        ) return false
        val directionSign = direction.sign.takeUnless { it == 0f } ?: 1f
        spinVelocity += directionSign * min(magnitude, threshold * 3f) * settings.flickSensitivity
        lastFlickNanos = monotonicNanos
        activity.markMeaningfulMotion(monotonicNanos)
        return true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val MAX_TILT_RADIANS = 0.45f
        const val GYRO_THRESHOLD = 2.5f
        const val ACCELERATION_THRESHOLD = 6f
        const val FLICK_COOLDOWN_NANOS = 450_000_000L
        const val TAP_SPIN_RADIANS_PER_SECOND = 7f
        const val SENSOR_LOW_PASS_ALPHA = 0.18f
        const val MEANINGFUL_MOTION_DELTA = 0.01f
    }
}
