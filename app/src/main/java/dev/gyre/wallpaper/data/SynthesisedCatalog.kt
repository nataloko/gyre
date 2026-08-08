package dev.gyre.wallpaper.data

import dev.gyre.wallpaper.model.Design
import dev.gyre.wallpaper.model.Layer
import dev.gyre.wallpaper.model.PaletteColors
import dev.gyre.wallpaper.model.PreviewAssets
import dev.gyre.wallpaper.model.Remix
import dev.gyre.wallpaper.model.RotationDirection
import dev.gyre.wallpaper.model.RotationSpec

/**
 * The catalogue entry a picture gets when it arrives without one.
 *
 * A folder of photographs becomes one **piece** with a **variant** per image, rather than a piece
 * each. That is the shape the interface already has: the variant strip becomes "the photos in this
 * folder", the two-finger tap that steps through variants becomes "the next photo", and a
 * two-hundred-image import adds one tile to the collection instead of drowning it.
 */
object SynthesisedCatalog {
    class Image(
        val fileName: String,
        val masterFile: String,
        val thumbFile: String,
        val palette: PaletteColors,
        val isDark: Boolean,
    )

    /**
     * The catalogue for [images], all under one design.
     *
     * The motion values match the bundled artwork rather than being invented: the same slow turn,
     * the same tilt-only parallax, and a scaler whose product with the spin wrap period is a whole
     * number of turns — which `BundledCatalogRepository.wrapsWithTheSpinPeriod` requires of
     * everything the renderer is given, imported or not.
     */
    fun forImages(importId: String, label: String, images: List<Image>): CatalogSnapshot {
        val designId = ImportedCatalog.idFor(importId, DESIGN_SUFFIX)
        val used = mutableSetOf<String>()
        val remixes = images.mapIndexed { index, image ->
            Remix(
                id = ImportedCatalog.idFor(importId, "$VARIANT_PREFIX$index"),
                designId = designId,
                label = uniqueLabel(labelFor(image.fileName), used),
                isDark = image.isDark,
                isMultilayered = false,
                inputRotationScaler = INPUT_ROTATION_SCALER,
                type = "parallax",
                previews = PreviewAssets(ImportedCatalog.artworkPath(importId, image.thumbFile)),
                layers = listOf(
                    Layer(
                        imageUrl = ImportedCatalog.artworkPath(importId, image.masterFile),
                        type = "animated",
                        parallaxScale = PARALLAX_SCALE,
                        parallaxOnlyOnTilt = true,
                        rotation = RotationSpec(ROTATION_SECONDS, RotationDirection.CLOCKWISE),
                    ),
                ),
                colors = image.palette,
            )
        }
        val design = Design(
            id = designId,
            label = label,
            previewRemixId = remixes.first().id,
            remixIds = remixes.map(Remix::id),
        )
        return CatalogSnapshot(listOf(design), remixes)
    }

    /**
     * A file's name without its extension, and without a trailing "(Dark)".
     *
     * Theme twins pair by label — "Moss" and "Moss (Dark)" — so a photo called `Sunset (Dark).jpg`
     * would otherwise create half a pair inside its own design. Harmless in itself, but it is an
     * invariant the bundled catalogue is held to and there is no reason to import a violation of
     * it.
     */
    fun labelFor(fileName: String): String {
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
        return stem.removeSuffix(DARK_LABEL_SUFFIX).trim().ifBlank { "Untitled" }
    }

    private fun uniqueLabel(label: String, used: MutableSet<String>): String {
        if (used.add(label)) return label
        var index = 2
        while (!used.add("$label ($index)")) index++
        return "$label ($index)"
    }

    const val DESIGN_SUFFIX = "images"
    private const val VARIANT_PREFIX = "image"
    private const val DARK_LABEL_SUFFIX = " (Dark)"

    /** 0.75 x MotionMath.SPIN_WRAP_TURNS is three whole turns, as every bundled remix is. */
    private const val INPUT_ROTATION_SCALER = 0.75f
    private const val PARALLAX_SCALE = 0.4f
    private const val ROTATION_SECONDS = 180f
}
