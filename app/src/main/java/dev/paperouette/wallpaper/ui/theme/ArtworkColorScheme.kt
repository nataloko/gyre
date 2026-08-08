package dev.paperouette.wallpaper.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import dev.paperouette.wallpaper.model.PaletteColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Builds the interface palette from the artwork on screen.
 *
 * The catalogue already ships a palette per remix — the same one the wallpaper hands to the system
 * as `WallpaperColors` — so the app can dress itself in the colours of whatever the user is looking
 * at instead of a fixed brand palette.
 *
 * Tones are stepped in HSL rather than HCT: `material-color-utilities` is not a dependency and the
 * copy inside material3 is internal. HSL is perceptually cruder, so nothing here trusts a tone to
 * be legible by construction — every foreground is chosen by measured contrast, and
 * `ArtworkColorSchemeTest` checks the result against every remix in the catalogue.
 */
object ArtworkColorScheme {
    /** Minimum contrast for text and icons, matching WCAG AA for body text. */
    const val MIN_CONTRAST = 4.5

    /** Below this a filled control stops standing out from the page behind it. */
    const val MIN_ACCENT_CONTRAST = 3.0

    /**
     * The interface's own palette: a fixed near-black chassis wearing the artwork only as accents.
     *
     * An earlier scheme tinted every surface with the artwork, which suited a page of cards on a
     * page of its own. The live-first interface floats its panels straight on top of the artwork,
     * and a panel washed in the same hue as the picture behind it dissolves into it. So surfaces
     * here are neutral and hold still while the artwork changes; only the accents — active states,
     * faders, the primary button — follow the picture.
     *
     * Always dark, whatever the system theme says, because light chrome over full-screen artwork is
     * not legible at any surface tone. The system theme still chooses which artwork is selected,
     * through `PaperouetteSettings.automaticDarkVariants`; that is a separate thing.
     */
    fun chassis(colors: PaletteColors): ColorScheme {
        val seed = colors.vibrantColor ?: colors.loadingColor
        val primary = accent(seed, CHASSIS_BACKGROUND, dark = true)
        val secondary = accent(colors.mutedColor ?: colors.darkVibrantColor ?: seed, CHASSIS_BACKGROUND, dark = true)
        val tertiary = accent(
            colors.lightVibrantColor ?: colors.darkMutedColor ?: seed,
            CHASSIS_BACKGROUND,
            dark = true,
        )
        return darkColorScheme().copy(
            primary = primary,
            onPrimary = readableOn(primary),
            primaryContainer = container(primary, dark = true),
            onPrimaryContainer = readableOn(container(primary, dark = true)),
            secondary = secondary,
            onSecondary = readableOn(secondary),
            secondaryContainer = container(secondary, dark = true),
            onSecondaryContainer = readableOn(container(secondary, dark = true)),
            tertiary = tertiary,
            onTertiary = readableOn(tertiary),
            tertiaryContainer = container(tertiary, dark = true),
            onTertiaryContainer = readableOn(container(tertiary, dark = true)),
            background = CHASSIS_BACKGROUND,
            onBackground = Color.White,
            surface = CHASSIS_SURFACE,
            onSurface = Color.White,
            surfaceVariant = CHASSIS_SURFACE_VARIANT,
            onSurfaceVariant = CHASSIS_ON_SURFACE_VARIANT,
            surfaceContainerLowest = CHASSIS_CONTAINER_LOWEST,
            surfaceContainerLow = CHASSIS_CONTAINER_LOW,
            surfaceContainer = CHASSIS_CONTAINER,
            surfaceContainerHigh = CHASSIS_CONTAINER_HIGH,
            surfaceContainerHighest = CHASSIS_CONTAINER_HIGHEST,
            surfaceTint = primary,
            inverseSurface = Color.White,
            inverseOnSurface = CHASSIS_SURFACE,
            outline = CHASSIS_OUTLINE,
            outlineVariant = CHASSIS_OUTLINE_VARIANT,
            scrim = Color.Black,
        )
    }

    /**
     * Keeps an artwork colour's hue while moving it to a tone that stands out from [background].
     *
     * HSL lightness is not perceptual — a saturated yellow and a saturated blue at the same
     * lightness differ by roughly 5× in luminance — so aiming at a fixed lightness gave accents
     * that vanished into a pale page. Instead the tone steps away from the background until the
     * measured contrast clears [MIN_ACCENT_CONTRAST].
     */
    private fun accent(argb: Int, background: Color, dark: Boolean): Color {
        val (hue, rawSaturation, rawLightness) = hsl(argb)
        val saturation = max(rawSaturation, MIN_ACCENT_SATURATION)
        val start = if (dark) rawLightness.coerceIn(0.62f, 0.86f) else rawLightness.coerceIn(0.28f, 0.46f)
        val step = if (dark) TONE_STEP else -TONE_STEP

        var lightness = start
        repeat(TONE_STEPS) {
            val candidate = fromHsl(hue, saturation, lightness)
            if (contrast(candidate, background) >= MIN_ACCENT_CONTRAST) return candidate
            lightness = (lightness + step).coerceIn(0f, 1f)
        }
        return fromHsl(hue, saturation, if (dark) 1f else 0f)
    }

    private fun container(accent: Color, dark: Boolean): Color {
        val (hue, saturation, _) = hsl(accent.toArgbInt())
        return fromHsl(hue, saturation * 0.85f, if (dark) 0.24f else 0.86f)
    }

    /**
     * Lifts [argb] until the accent drawn from it reads on the chassis' lightest surface.
     *
     * `generate_catalog.py` does this before it renders anything, so every bundled variant arrives
     * pre-cleared and `ArtworkColorSchemeTest` passes over the whole catalogue. Artwork the user
     * imports has had no such pass, and this is where it gets one — the same arithmetic the
     * interface itself uses, so an imported piece cannot dress the chrome in something the test
     * would have failed.
     *
     * A saturated blue is the case that bites. [accent] stops as soon as a colour clears the
     * near-black *background*, which a blue at lightness 0.62 does — but blue carries only 7% of
     * perceived luminance, so the same colour then fails against the container surfaces the chips
     * and fader tracks actually sit on. Raising the lightness here fixes it at the source, where
     * there is still a hue to preserve.
     */
    internal fun legibleSeed(argb: Int): Int {
        val (hue, saturation, start) = hsl(argb)
        var lightness = start
        repeat(SEED_STEPS) {
            val candidate = fromHsl(hue, saturation, lightness)
            // Drawn the way the chrome will draw it — against the background — and then checked
            // against the container it will actually sit on. Lifting against the container
            // directly would be answering a question nothing asks.
            val drawn = accent(candidate.toArgbInt(), CHASSIS_BACKGROUND, dark = true)
            if (contrast(drawn, CHASSIS_CONTAINER_HIGHEST) >= ACCENT_MARGIN) {
                return candidate.toArgbInt()
            }
            if (lightness >= 1f) return@repeat
            lightness = (lightness + SEED_STEP).coerceAtMost(1f)
        }
        return fromHsl(hue, saturation, 1f).toArgbInt()
    }

    /** Black or white, whichever reads better on [background]. */
    fun readableOn(background: Color): Color =
        if (contrast(Color.White, background) >= contrast(Color.Black, background)) {
            Color.White
        } else {
            Color.Black
        }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    fun contrast(foreground: Color, background: Color): Double {
        val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
        val darker = min(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * WCAG relative luminance, which gamma-expands each channel first.
     *
     * Deliberately different from `PaletteTransform`'s raw-channel luminance in
     * `render/RenderModels.kt`: that one drives greyscale mixing, where working on the values the
     * shader already has is the point.
     */
    private fun relativeLuminance(color: Color): Double {
        fun expand(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * expand(color.red) + 0.7152 * expand(color.green) + 0.0722 * expand(color.blue)
    }

    private fun hsl(argb: Int): Triple<Float, Float, Float> {
        val red = (argb ushr 16 and 0xff) / 255f
        val green = (argb ushr 8 and 0xff) / 255f
        val blue = (argb and 0xff) / 255f
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val delta = maximum - minimum
        val lightness = (maximum + minimum) / 2f
        if (delta < 1e-5f) return Triple(0f, 0f, lightness)
        val saturation = delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-5f)
        val hue = when (maximum) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
        return Triple((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
    }

    private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Color {
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation.coerceIn(0f, 1f)
        val sector = ((hue % 360f) + 360f) % 360f / 60f
        val secondary = chroma * (1f - abs(sector % 2f - 1f))
        val (red, green, blue) = when (sector.toInt()) {
            0 -> Triple(chroma, secondary, 0f)
            1 -> Triple(secondary, chroma, 0f)
            2 -> Triple(0f, chroma, secondary)
            3 -> Triple(0f, secondary, chroma)
            4 -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        val match = lightness - chroma / 2f
        return Color(
            red = (red + match).coerceIn(0f, 1f),
            green = (green + match).coerceIn(0f, 1f),
            blue = (blue + match).coerceIn(0f, 1f),
        )
    }

    private fun Color.toArgbInt(): Int =
        (0xff shl 24) or
            ((red * 255f).toInt() shl 16) or
            ((green * 255f).toInt() shl 8) or
            (blue * 255f).toInt()

    /**
     * The chassis tones, held apart far enough to read as separate layers when each is drawn
     * translucently over moving artwork rather than over the one below it.
     */
    private val CHASSIS_BACKGROUND = Color(0xFF08080A)
    private val CHASSIS_SURFACE = Color(0xFF0D0D10)
    private val CHASSIS_CONTAINER_LOWEST = Color(0xFF060608)
    private val CHASSIS_CONTAINER_LOW = Color(0xFF121216)
    private val CHASSIS_CONTAINER = Color(0xFF17171C)
    private val CHASSIS_CONTAINER_HIGH = Color(0xFF1F1F25)
    private val CHASSIS_CONTAINER_HIGHEST = Color(0xFF27272E)
    private val CHASSIS_SURFACE_VARIANT = Color(0xFF2C2C34)
    private val CHASSIS_ON_SURFACE_VARIANT = Color(0xFFC8C8D2)
    private val CHASSIS_OUTLINE = Color(0xFF6E6E7A)
    private val CHASSIS_OUTLINE_VARIANT = Color(0xFF34343C)

    /** Keeps washed-out artwork from producing grey chrome. */
    private const val MIN_ACCENT_SATURATION = 0.42f

    /**
     * A little above the 3.0 [MIN_ACCENT_CONTRAST] demands, so that rounding cannot land an
     * imported palette just under the line.
     */
    private const val ACCENT_MARGIN = 3.25

    private const val SEED_STEP = 0.02f
    private const val SEED_STEPS = 40

    private const val TONE_STEP = 0.02f
    private const val TONE_STEPS = 50
}
