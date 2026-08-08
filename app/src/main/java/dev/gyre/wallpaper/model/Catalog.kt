package dev.gyre.wallpaper.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Catalog(
    val designIds: List<String>,
    val remixIds: List<String>,
    val designs: List<Design>,
    val remixes: List<Remix>,
)

@Serializable
data class Design(
    val id: String,
    val label: String,
    val previewRemixId: String,
    val remixIds: List<String>,
)

@Serializable
data class Remix(
    val id: String,
    val designId: String,
    val label: String,
    val isDark: Boolean,
    val isMultilayered: Boolean,
    val inputRotationScaler: Float = 1f,
    val type: String,
    val previews: PreviewAssets,
    val layers: List<Layer>,
    val colors: PaletteColors,
)

/**
 * The one preview that ships.
 *
 * The source catalogue also carries `multiColumn` and `singleColumn` renderings at 1300 and 1744
 * square. Together they were 161 MiB — nearly half the APK — to serve tiles that are never wider
 * than about 480 px, so the importer drops them and guarantees every remix a thumb instead. The
 * reference images the renderer's visual tests compare against now live in the test's own assets.
 */
@Serializable
data class PreviewAssets(val thumb: String)

@Serializable
data class Layer(
    val imageUrl: String,
    val type: String,
    val parallaxScale: Float = 0f,
    val parallaxOnlyOnTilt: Boolean = false,
    val rotation: RotationSpec? = null,
    val imageSubsetLayoutParams: SubsetLayout? = null,
    /**
     * The colours this layer's mask resolves to, or null when [imageUrl] is already coloured.
     *
     * Where a layer is one hue over a coverage field — every line-art piece, and where nearly all
     * of the artwork's weight sits — the image is a greyscale mask and the colour lives here. Every
     * colour variant of a piece then shares one mask and differs by a handful of numbers, rather
     * than being another full copy of the same geometry.
     */
    val ramp: List<RampStop>? = null,
    /**
     * Whether the mask holds a wrapping value rather than a coverage that ends.
     *
     * A cyclic quantity cannot be interpolated as a scalar: either side of the wrap the texels
     * hold 1 and 0, and every sampler between them reports the far side of the ramp. So these
     * masks carry `(cos, sin)` in red and green instead, which interpolates to the right angle,
     * and whose length says how much of the cycle survived the filtering.
     */
    val cyclic: Boolean = false,
)

/**
 * One stop of a layer's colour ramp: a position in `0..1` along the mask, and the ARGB it means.
 *
 * Ramps interpolate, deliberately. A discrete index map cannot survive bilinear filtering or a
 * mipmap — halfway between index 0.1 and 0.9 is 0.5, a colour belonging to neither band — whereas
 * a ramp is meaningful everywhere between its stops.
 */
@Serializable
data class RampStop(val at: Float, val color: Int)

@Serializable
data class RotationSpec(
    val time: Float,
    val direction: RotationDirection,
)

@Serializable
enum class RotationDirection {
    @SerialName("clockwise")
    CLOCKWISE,

    @SerialName("anticlockwise")
    ANTICLOCKWISE,
}

@Serializable
data class SubsetLayout(
    val xRatio: Float,
    val yRatio: Float,
    val sceneWidthRatio: Float,
    val sceneHeightRatio: Float,
) {
    companion object {
        val Full = SubsetLayout(0f, 0f, 1f, 1f)
    }
}

@Serializable
data class PaletteColors(
    val loadingColor: Int,
    val vibrantColor: Int? = null,
    val darkVibrantColor: Int? = null,
    val lightVibrantColor: Int? = null,
    val mutedColor: Int? = null,
    val darkMutedColor: Int? = null,
    val lightMutedColor: Int? = null,
)

