"""The colour arithmetic the interface will apply to whatever the generator emits.

This is a deliberate second copy of `ui/theme/ArtworkColorScheme.kt`. The interface takes its
accents from the artwork and then has to stay legible on a near-black chassis, which
`ArtworkColorSchemeTest` asserts for every variant in the catalogue. Discovering a failure there
means a three-hundred-variant render was wasted, so the generator answers the same question before
it draws anything and lifts any accent that would not have cleared.

Keeping the two in step matters. The Kotlin side remains the authority — it is what actually runs —
and the test is what proves this copy still agrees with it.
"""

from __future__ import annotations

import colorsys

# ArtworkColorScheme.CHASSIS_BACKGROUND and CHASSIS_CONTAINER_HIGHEST.
CHASSIS_BACKGROUND = (0x08, 0x08, 0x0A)
CHASSIS_CONTAINER_HIGHEST = (0x27, 0x27, 0x2E)

MIN_ACCENT_CONTRAST = 3.0
MIN_ACCENT_SATURATION = 0.32
TONE_STEP = 0.04
TONE_STEPS = 12

# A little above the 3.0 the test demands, so that rounding between this arithmetic and Kotlin's
# cannot land a variant just under the line.
ACCENT_MARGIN = 3.25


def relative_luminance(rgb: tuple[int, int, int]) -> float:
    """WCAG relative luminance, gamma-expanded per channel."""

    def expand(channel: int) -> float:
        value = channel / 255.0
        return value / 12.92 if value <= 0.03928 else ((value + 0.055) / 1.055) ** 2.4

    red, green, blue = rgb
    return 0.2126 * expand(red) + 0.7152 * expand(green) + 0.0722 * expand(blue)


def contrast(first: tuple[int, int, int], second: tuple[int, int, int]) -> float:
    lighter = max(relative_luminance(first), relative_luminance(second))
    darker = min(relative_luminance(first), relative_luminance(second))
    return (lighter + 0.05) / (darker + 0.05)


def to_hsl(rgb: tuple[int, int, int]) -> tuple[float, float, float]:
    red, green, blue = (channel / 255.0 for channel in rgb)
    hue, lightness, saturation = colorsys.rgb_to_hls(red, green, blue)
    return hue, saturation, lightness


def from_hsl(hue: float, saturation: float, lightness: float) -> tuple[int, int, int]:
    red, green, blue = colorsys.hls_to_rgb(hue, lightness, saturation)
    return (
        round(max(0.0, min(1.0, red)) * 255),
        round(max(0.0, min(1.0, green)) * 255),
        round(max(0.0, min(1.0, blue)) * 255),
    )


def accent(rgb: tuple[int, int, int]) -> tuple[int, int, int]:
    """What `ArtworkColorScheme.accent` will turn this colour into on the dark chassis."""
    hue, raw_saturation, raw_lightness = to_hsl(rgb)
    saturation = max(raw_saturation, MIN_ACCENT_SATURATION)
    lightness = min(max(raw_lightness, 0.62), 0.86)
    for _ in range(TONE_STEPS):
        candidate = from_hsl(hue, saturation, lightness)
        if contrast(candidate, CHASSIS_BACKGROUND) >= MIN_ACCENT_CONTRAST:
            return candidate
        lightness = min(1.0, lightness + TONE_STEP)
    return from_hsl(hue, saturation, 1.0)


def legible_seed(rgb: tuple[int, int, int]) -> tuple[int, int, int]:
    """Lift [rgb] until the accent drawn from it reads on the chassis' lightest surface.

    A saturated blue is the case that bites. `accent` stops as soon as the colour clears the
    near-black *background*, which a blue at lightness 0.62 does — but blue carries only 7% of
    perceived luminance, so the same colour then fails against the container surfaces the chips and
    fader tracks actually sit on. Raising the lightness here fixes it at the source, where there is
    still a hue to preserve.
    """
    hue, saturation, lightness = to_hsl(rgb)
    for _ in range(40):
        candidate = from_hsl(hue, saturation, lightness)
        if contrast(accent(candidate), CHASSIS_CONTAINER_HIGHEST) >= ACCENT_MARGIN:
            return candidate
        lightness = min(1.0, lightness + 0.02)
        if lightness >= 1.0:
            break
    return from_hsl(hue, saturation, 1.0)


def to_argb(rgb: tuple[int, int, int]) -> int:
    """Packed ARGB as the catalogue stores it — a signed 32-bit int, as Kotlin reads it."""
    red, green, blue = rgb
    value = (0xFF << 24) | (red << 16) | (green << 8) | blue
    return value - (1 << 32) if value >= (1 << 31) else value
