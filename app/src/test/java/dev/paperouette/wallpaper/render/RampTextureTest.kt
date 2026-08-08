package dev.paperouette.wallpaper.render

import dev.paperouette.wallpaper.model.RampStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ramp is what a colour variant *is* now, so its arithmetic is worth pinning down off-device.
 *
 * The premultiplication is the part that bites: the renderer blends with
 * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`, so a ramp built with straight alpha would look right in
 * isolation and fringe against every layer drawn beside it.
 */
class RampTextureTest {
    @Test
    fun aRampSpansItsStopsAcrossTheWholeTexture() {
        val bytes = RampTexture.build(
            listOf(RampStop(0f, BLACK), RampStop(1f, WHITE)),
        )

        assertEquals(RampTexture.WIDTH * 4, bytes.size)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(255, bytes[bytes.size - 4].toInt() and 0xff)
    }

    @Test
    fun coloursArePremultipliedByTheirOwnAlpha() {
        // Half-transparent white: every colour channel should come back at half strength, because
        // that is what the blend function expects to receive.
        val bytes = RampTexture.build(listOf(RampStop(0f, 0x80FFFFFF.toInt())))

        assertEquals(0x80, bytes[3].toInt() and 0xff)
        assertEquals(0x80, bytes[0].toInt() and 0xff)
        assertEquals(0x80, bytes[1].toInt() and 0xff)
        assertEquals(0x80, bytes[2].toInt() and 0xff)
    }

    /**
     * The case the line-art pieces actually take: transparent at one end, opaque colour at the
     * other. Premultiplied, the midpoint stays that colour at half strength. Interpolated straight
     * it would drift toward whatever the transparent stop's RGB happened to be, which is why the
     * transparent stop still carries a colour rather than being left at zero.
     */
    @Test
    fun aFadeToTransparentKeepsItsHue() {
        val bytes = RampTexture.build(
            listOf(RampStop(0f, 0x0000FF00), RampStop(1f, 0xFF00FF00.toInt())),
        )

        val middle = (RampTexture.WIDTH / 2) * 4
        val red = bytes[middle].toInt() and 0xff
        val green = bytes[middle + 1].toInt() and 0xff
        val blue = bytes[middle + 2].toInt() and 0xff
        val alpha = bytes[middle + 3].toInt() and 0xff
        assertTrue("midpoint should be about half opaque, was $alpha", alpha in 120..135)
        assertEquals("green should equal alpha once premultiplied", alpha, green)
        assertEquals(0, red)
        assertEquals(0, blue)
    }

    @Test
    fun stopsOutOfOrderAreSortedRatherThanTrusted() {
        val ascending = RampTexture.build(listOf(RampStop(0f, BLACK), RampStop(1f, WHITE)))
        val descending = RampTexture.build(listOf(RampStop(1f, WHITE), RampStop(0f, BLACK)))

        assertTrue(ascending.contentEquals(descending))
    }

    @Test
    fun aSingleStopFillsTheWholeRamp() {
        val bytes = RampTexture.build(listOf(RampStop(0.5f, WHITE)))

        assertEquals(255, bytes[0].toInt() and 0xff)
        assertEquals(255, bytes[bytes.size - 1].toInt() and 0xff)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
