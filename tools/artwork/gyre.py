"""Layered square vortices derived from Gyre's launcher icon geometry."""

from __future__ import annotations

from dataclasses import dataclass
import math

from PIL import Image, ImageDraw


@dataclass(frozen=True, slots=True)
class GyrePalette:
    """The paper, repeating ink, and central accent used by one variant."""

    paper: str
    ink: str
    accent: str


GYRE: dict[str, GyrePalette] = {
    # The first variant uses the app icon's exact three colours.
    "0": GyrePalette(paper="#F5F0E4", ink="#12263F", accent="#F0508C"),
    "1": GyrePalette(paper="#E8F3F0", ink="#164E63", accent="#2CBF9F"),
    "2": GyrePalette(paper="#081521", ink="#E8E0D0", accent="#FF5C93"),
    "3": GyrePalette(paper="#EFE5D1", ink="#285247", accent="#E96B4C"),
    "4": GyrePalette(paper="#281C32", ink="#F2E5D2", accent="#F3B33D"),
    "5": GyrePalette(paper="#EEE9DD", ink="#273E91", accent="#F04C7C"),
}

# These are the launcher icon's proportions. Its axis is deliberately offset;
# the wallpaper version keeps the same stack but puts that axis at dead centre.
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
# only line here that is not in the upstream copy in swirls2.
GYRE_DESIGN_IDS = frozenset({"spinner_gyre"})


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


def render_gyre(
    variant: str,
    width: int,
    height: int,
    layer_index: int,
) -> Image.Image:
    """Render one layer of a centred, independently rotating square stack."""

    palette = GYRE[variant]
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
