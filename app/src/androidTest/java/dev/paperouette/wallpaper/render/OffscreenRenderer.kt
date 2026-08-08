package dev.paperouette.wallpaper.render

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import dev.paperouette.wallpaper.model.Remix
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class OffscreenRenderer(
    context: Context,
    val width: Int = 180,
    val height: Int = 320,
) : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val renderer = SceneRenderer(context)

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0))
        check(count[0] == 1)
        val config = requireNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext))
        renderer.initialize()
    }

    fun render(
        remix: Remix,
        filters: FilterState = FilterState(),
        rotationCenter: RotationCenter = RotationCenter.Center,
        monotonicNanos: Long = 0L,
        mirrored: Boolean = false,
        rotationReversed: Boolean = false,
    ): PixelFrame {
        renderer.render(
            scene = remix,
            viewport = Viewport(width, height),
            monotonicNanos = monotonicNanos,
            motion = MotionState(),
            filters = filters,
            rotationCenter = rotationCenter,
            mirrored = mirrored,
            rotationReversed = rotationReversed,
        )
        GLES30.glFinish()
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            bytes,
        )
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val source = ((height - 1 - y) * width + x) * 4
                val red = bytes.get(source).toInt() and 0xff
                val green = bytes.get(source + 1).toInt() and 0xff
                val blue = bytes.get(source + 2).toInt() and 0xff
                val alpha = bytes.get(source + 3).toInt() and 0xff
                pixels[y * width + x] = (alpha shl 24) or
                    (red shl 16) or
                    (green shl 8) or
                    blue
            }
        }
        return PixelFrame(width, height, pixels)
    }

    fun trimMemory() = renderer.trimMemory()

    fun debugSnapshot() = renderer.debugSnapshot()

    override fun close() {
        renderer.release()
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    }
}

internal data class PixelFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    /**
     * Share of pixels exactly [color], for spotting the cleared background through a scene that
     * should be covering it. Only meaningful against artwork that contains no such pixel itself.
     */
    fun fractionMatching(color: Int): Float =
        pixels.count { it == color }.toFloat() / pixels.size

    /** This frame reflected left to right, for checking what the mirror setting should produce. */
    fun flippedHorizontally(): PixelFrame {
        val flipped = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                flipped[y * width + x] = pixels[y * width + (width - 1 - x)]
            }
        }
        return PixelFrame(width, height, flipped)
    }

    fun meanAbsoluteRgbError(other: PixelFrame): Float {
        require(width == other.width && height == other.height)
        var error = 0L
        pixels.indices.forEach { index ->
            val first = pixels[index]
            val second = other.pixels[index]
            error += kotlin.math.abs((first shr 16 and 0xff) - (second shr 16 and 0xff))
            error += kotlin.math.abs((first shr 8 and 0xff) - (second shr 8 and 0xff))
            error += kotlin.math.abs((first and 0xff) - (second and 0xff))
        }
        return error.toFloat() / pixels.size / 3f / 255f
    }

    fun structuralSimilarity(other: PixelFrame): Float {
        require(width == other.width && height == other.height)
        val first = pixels.map(::luminance)
        val second = other.pixels.map(::luminance)
        val firstMean = first.average()
        val secondMean = second.average()
        var firstVariance = 0.0
        var secondVariance = 0.0
        var covariance = 0.0
        first.indices.forEach { index ->
            val firstDelta = first[index] - firstMean
            val secondDelta = second[index] - secondMean
            firstVariance += firstDelta * firstDelta
            secondVariance += secondDelta * secondDelta
            covariance += firstDelta * secondDelta
        }
        val divisor = (first.size - 1).coerceAtLeast(1)
        firstVariance /= divisor
        secondVariance /= divisor
        covariance /= divisor
        val c1 = 0.01 * 0.01
        val c2 = 0.03 * 0.03
        return (
            (2 * firstMean * secondMean + c1) * (2 * covariance + c2) /
                ((firstMean * firstMean + secondMean * secondMean + c1) *
                    (firstVariance + secondVariance + c2))
            ).toFloat()
    }

    fun meanLuminance(): Float = pixels.sumOf { pixel ->
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        (red * 0.2126 + green * 0.7152 + blue * 0.0722)
    }.toFloat() / pixels.size / 255f

    fun meanChroma(): Float = pixels.sumOf { pixel ->
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        (maxOf(red, green, blue) - minOf(red, green, blue)).toDouble()
    }.toFloat() / pixels.size / 255f

    fun edgeEnergy(): Float {
        var energy = 0L
        var samples = 0
        for (y in 0 until height step 2) {
            for (x in 0 until width - 1 step 2) {
                val first = pixels[y * width + x]
                val second = pixels[y * width + x + 1]
                energy += kotlin.math.abs((first shr 16 and 0xff) - (second shr 16 and 0xff))
                energy += kotlin.math.abs((first shr 8 and 0xff) - (second shr 8 and 0xff))
                energy += kotlin.math.abs((first and 0xff) - (second and 0xff))
                samples += 3
            }
        }
        return energy.toFloat() / samples / 255f
    }

    private fun luminance(pixel: Int): Double {
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        return (red * 0.2126 + green * 0.7152 + blue * 0.0722) / 255.0
    }
}
