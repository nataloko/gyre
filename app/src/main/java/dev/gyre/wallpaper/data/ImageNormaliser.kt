package dev.gyre.wallpaper.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import androidx.core.graphics.scale
import dev.gyre.wallpaper.model.PaletteColors
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Turns whatever the user picked into artwork the renderer can treat like any other.
 *
 * Four things happen here, and none of them is cosmetic:
 *
 * **Square.** The shader turns the scene in normalised coordinates, so a rotation is only a
 * rotation when the artwork is square — on anything else it is a shear, and the picture stretches
 * as it turns. Cropping is what buys an imported photo the same spin, flick and nudge the bundled
 * artwork has.
 *
 * **Bounded.** `SceneRenderer` only halves its decode while the result is still comfortably larger
 * than the viewport, so an 8000-pixel photograph would arrive whole and fail the GL maximum
 * texture size on any device that reports 4096 — which stops the wallpaper drawing until something
 * else is selected.
 *
 * **Re-encoded**, which also strips EXIF: an imported photograph's location does not follow it
 * into the app's storage.
 *
 * **Measured**, so the piece has a loading colour, an accent and a tone like every other.
 */
internal object ImageNormaliser {
    /** The bundled artwork's own master edge, so imports sample no differently. */
    const val MASTER_EDGE = 2048

    /** The bundled thumb edge. */
    const val THUMB_EDGE = 260

    class Normalised(
        val master: ByteArray,
        val thumb: ByteArray,
        val palette: PaletteColors,
        val isDark: Boolean,
    )

    /** [bytes] as a square master, a thumb and a palette, or null if it is not an image at all. */
    fun normalise(bytes: ByteArray): Normalised? = runCatching {
        val square = decodeSquare(bytes)
        try {
            val thumb = square.scale(THUMB_EDGE, THUMB_EDGE)
            try {
                val pixels = IntArray(THUMB_EDGE * THUMB_EDGE)
                thumb.getPixels(pixels, 0, THUMB_EDGE, 0, 0, THUMB_EDGE, THUMB_EDGE)
                val loading = ArtworkPalette.meanColor(pixels)
                Normalised(
                    master = encode(square, MASTER_QUALITY),
                    thumb = encode(thumb, THUMB_QUALITY),
                    palette = ArtworkPalette.paletteFor(pixels),
                    // Measured from the thumb, exactly as the generator measures a render's tone,
                    // so an imported piece and a bundled one mean the same thing by "dark".
                    isDark = ArtworkPalette.isDark(loading),
                )
            } finally {
                if (thumb !== square) thumb.recycle()
            }
        } finally {
            square.recycle()
        }
    }.getOrNull()

    /**
     * The middle square of [bytes], no larger than [MASTER_EDGE] and never enlarged.
     *
     * The decoder does the scaling and the cropping together, so a large photograph is never held
     * in memory at its original size — and `ImageDecoder` applies the EXIF orientation on the way,
     * which is what keeps a portrait photo from arriving on its side.
     */
    private fun decodeSquare(bytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val shortEdge = minOf(width, height)
            val scale = if (shortEdge > MASTER_EDGE) MASTER_EDGE.toFloat() / shortEdge else 1f
            val targetWidth = maxOf(1, (width * scale).toInt())
            val targetHeight = maxOf(1, (height * scale).toInt())
            val edge = minOf(targetWidth, targetHeight)
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.setCrop(
                Rect(
                    (targetWidth - edge) / 2,
                    (targetHeight - edge) / 2,
                    (targetWidth - edge) / 2 + edge,
                    (targetHeight - edge) / 2 + edge,
                ),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { out ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) {
                "Cannot encode ${bitmap.width}x${bitmap.height}"
            }
        }.toByteArray()

    private const val MASTER_QUALITY = 90
    private const val THUMB_QUALITY = 90
}
