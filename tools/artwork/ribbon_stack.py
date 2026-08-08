"""Layered square ribbons turning independently around one shared centre."""

from __future__ import annotations

from dataclasses import dataclass
import math

from PIL import Image, ImageDraw


@dataclass(frozen=True, slots=True)
class RibbonStackPalette:
    """The paper, repeating ink, and central accent used by one variant."""

    paper: str
    ink: str
    accent: str


RIBBON_STACK: dict[str, RibbonStackPalette] = {
    "0": RibbonStackPalette(paper="#F5F0E4", ink="#12263F", accent="#F0508C"),
    "1": RibbonStackPalette(paper="#E8F3F0", ink="#164E63", accent="#2CBF9F"),
    "2": RibbonStackPalette(paper="#081521", ink="#E8E0D0", accent="#FF5C93"),
    "3": RibbonStackPalette(paper="#EFE5D1", ink="#285247", accent="#E96B4C"),
    "4": RibbonStackPalette(paper="#281C32", ink="#F2E5D2", accent="#F3B33D"),
    "5": RibbonStackPalette(paper="#EEE9DD", ink="#273E91", accent="#F04C7C"),
}

# The square plates share one centred axis while shrinking and turning through the stack.
SPAN = 1.10
PLATES = 19
TWIST = math.radians(104.0)
SHRINK = 0.965

# Nine alternating square bands are divided by depth so each group can rotate
# independently while their shared axis remains fixed.
BAND_GROUPS: tuple[tuple[int, ...], ...] = (
    (0, 1, 2),
    (3, 4, 5),
    (6, 7, 8),
)

# The families this module renders, the way circle_limits.py carries its own. The
# only line here that is not in the upstream copy these renderers were vendored from.
RIBBON_STACK_DESIGN_IDS = frozenset({"spinner_ribbon_stack"})


def _rgb(value: str) -> tuple[int, int, int]:
    clean = value.removeprefix("#")
    return tuple(int(clean[index : index + 2], 16) for index in (0, 2, 4))


def _plate(
    index: int,
    width: int,
    height: int,
    scale: int,
) -> list[tuple[float, float]]:
    """Return one centred plate's corners in oversampled pixel coordinates."""

    cx = width * scale / 2.0
    cy = height * scale / 2.0
    u = index / (PLATES - 1)
    radius = SPAN * min(width, height) * scale * (1.0 - u * SHRINK)
    rotation = TWIST * u
    return [
        (
            cx + radius * math.cos(rotation + math.pi / 4.0 + corner * math.pi / 2.0),
            cy + radius * math.sin(rotation + math.pi / 4.0 + corner * math.pi / 2.0),
        )
        for corner in range(4)
    ]


def _mask(
    width: int,
    height: int,
    *,
    bands: tuple[int, ...] = (),
    core: bool = False,
) -> Image.Image:
    """Rasterize square rings with antialiased edges into an alpha mask."""

    scale = 4 if min(width, height) <= 512 else 2
    mask = Image.new("L", (width * scale, height * scale), 0)
    draw = ImageDraw.Draw(mask)
    for band in bands:
        outer = band * 2
        draw.polygon(_plate(outer, width, height, scale), fill=255)
        draw.polygon(_plate(outer + 1, width, height, scale), fill=0)
    if core:
        draw.polygon(_plate(PLATES - 1, width, height, scale), fill=255)
    return mask.resize((width, height), Image.Resampling.LANCZOS)


def _colored_mask(size: tuple[int, int], color: str, alpha: Image.Image) -> Image.Image:
    image = Image.new("RGBA", size, (*_rgb(color), 0))
    image.putalpha(alpha)
    return image


def render_ribbon_stack(
    variant: str,
    width: int,
    height: int,
    layer_index: int,
) -> Image.Image:
    """Render one layer of a centred, independently rotating square stack."""

    palette = RIBBON_STACK[variant]
    if layer_index == 0:
        return Image.new("RGB", (width, height), _rgb(palette.paper))

    group = BAND_GROUPS[layer_index - 1]
    ink = _colored_mask((width, height), palette.ink, _mask(width, height, bands=group))
    if layer_index != len(BAND_GROUPS):
        return ink

    accent = _colored_mask(
        (width, height),
        palette.accent,
        _mask(width, height, core=True),
    )
    return Image.alpha_composite(ink, accent)
