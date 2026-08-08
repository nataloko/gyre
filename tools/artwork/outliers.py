"""Spinner families built on unfamiliar generative machinery.

Every family here is driven by a different system instead of a closed-form
spiral: a Gray-Scott reaction, a chaotic attractor histogram, quasiperiodic
wave interference, a log-polar Truchet tiling, and a circle-inversion group.
"""

from __future__ import annotations

from dataclasses import dataclass
import math

import numpy as np
from PIL import Image, ImageFilter

from .core import (
    blend,
    coordinate_grid,
    image_from_array,
    palette_image,
    parse_color,
    rng_for,
    smoothstep,
)


TAU = math.tau


@dataclass(frozen=True, slots=True)
class ReactorRecipe:
    """Gray-Scott chemistry swept from the core outwards."""

    colors: tuple[str, ...]
    feed: tuple[float, float]
    kill: tuple[float, float]
    swirl: float
    arms: int
    twist: float


@dataclass(frozen=True, slots=True)
class AttractorRecipe:
    colors: tuple[str, ...]
    kind: str
    params: tuple[float, float, float, float]
    symmetry: int
    steps: int
    gain: float


@dataclass(frozen=True, slots=True)
class QuasicrystalRecipe:
    colors: tuple[str, ...]
    waves: int
    frequency: float
    chirp: float
    bands: float
    overlay_waves: int
    overlay_frequency: float


@dataclass(frozen=True, slots=True)
class TruchetRecipe:
    colors: tuple[str, ...]
    cells: int
    split: int
    stroke: float
    jewel: str


@dataclass(frozen=True, slots=True)
class KleinianRecipe:
    colors: tuple[str, ...]
    mirrors: int
    snug: float
    inner: float
    iterations: int
    twist: float


REACTOR: dict[str, ReactorRecipe] = {
    "0": ReactorRecipe(
        colors=("#01131c", "#043b4f", "#0f8b8d", "#7ae7c7", "#f4fff8"),
        feed=(0.0300, 0.0545),
        kill=(0.0570, 0.0620),
        swirl=0.18,
        arms=5,
        twist=1.15,
    ),
    "1": ReactorRecipe(
        colors=("#12001f", "#3d0a52", "#8a1fa8", "#ff5edb", "#ffe6ff"),
        feed=(0.0140, 0.0260),
        kill=(0.0470, 0.0550),
        swirl=0.20,
        arms=3,
        twist=-1.6,
    ),
    "2": ReactorRecipe(
        colors=("#1a0a02", "#4d1a05", "#b4530c", "#ffab3d", "#fff0c9"),
        feed=(0.0220, 0.0340),
        kill=(0.0510, 0.0610),
        swirl=0.16,
        arms=7,
        twist=0.7,
    ),
    "3": ReactorRecipe(
        colors=("#05070d", "#1d2735", "#5d7185", "#b9d7e8", "#ffffff"),
        feed=(0.0460, 0.0300),
        kill=(0.0590, 0.0570),
        swirl=0.14,
        arms=4,
        twist=-0.95,
    ),
    "4": ReactorRecipe(
        colors=("#000000", "#0a2410", "#1f7a2e", "#8dff5a", "#e9ffd0"),
        feed=(0.0220, 0.0460),
        kill=(0.0610, 0.0590),
        swirl=0.14,
        arms=8,
        twist=1.85,
    ),
    "5": ReactorRecipe(
        colors=("#2b0f18", "#7d2340", "#d95f7a", "#ffc0c9", "#fff6ee"),
        feed=(0.0620, 0.0545),
        kill=(0.0609, 0.0620),
        swirl=0.19,
        arms=6,
        twist=-1.3,
    ),
}


ATTRACTOR: dict[str, AttractorRecipe] = {
    "0": AttractorRecipe(
        colors=("#03050f", "#0b1338", "#2f6df6", "#6ff2ff", "#ffffff"),
        kind="clifford",
        params=(-1.4, 1.6, 1.0, 0.7),
        symmetry=6,
        steps=240,
        gain=900.0,
    ),
    "1": AttractorRecipe(
        colors=("#0b0210", "#2c0733", "#a1179b", "#ff7ad9", "#fff0ff"),
        kind="dejong",
        params=(1.641, 1.902, 0.316, 1.525),
        symmetry=5,
        steps=240,
        gain=700.0,
    ),
    "2": AttractorRecipe(
        colors=("#0d0500", "#331803", "#a5590a", "#ffbe4f", "#fff6d8"),
        kind="clifford",
        params=(1.7, 1.7, 0.6, 1.2),
        symmetry=8,
        steps=220,
        gain=850.0,
    ),
    "3": AttractorRecipe(
        colors=("#00100c", "#03332a", "#0f9c7a", "#6dffd0", "#f0fff9"),
        kind="dejong",
        params=(-2.24, 0.43, -0.65, -2.43),
        symmetry=7,
        steps=240,
        gain=650.0,
    ),
    "4": AttractorRecipe(
        colors=("#100004", "#3b0316", "#c1123c", "#ff8a5b", "#fff2d4"),
        kind="clifford",
        params=(-1.8, -2.0, -0.5, -0.9),
        symmetry=9,
        steps=220,
        gain=800.0,
    ),
    "5": AttractorRecipe(
        colors=("#070713", "#191b45", "#5a53c9", "#b7a6ff", "#f4f0ff"),
        kind="dejong",
        params=(-2.7, -0.09, -0.86, -2.2),
        symmetry=12,
        steps=200,
        gain=600.0,
    ),
}


QUASICRYSTAL: dict[str, QuasicrystalRecipe] = {
    "0": QuasicrystalRecipe(
        colors=("#040814", "#123a5c", "#2ea9c9", "#9ef0e2", "#fff8d6"),
        waves=5,
        frequency=17.0,
        chirp=0.34,
        bands=5.0,
        overlay_waves=7,
        overlay_frequency=26.0,
    ),
    "1": QuasicrystalRecipe(
        colors=("#100108", "#4a0d2a", "#c02f6a", "#ff9a6b", "#ffeec4"),
        waves=7,
        frequency=13.0,
        chirp=0.52,
        bands=4.0,
        overlay_waves=5,
        overlay_frequency=21.0,
    ),
    "2": QuasicrystalRecipe(
        colors=("#01060a", "#062a2b", "#0f8f7a", "#68e0a4", "#e9ffdd"),
        waves=9,
        frequency=21.0,
        chirp=0.26,
        bands=7.0,
        overlay_waves=11,
        overlay_frequency=31.0,
    ),
    "3": QuasicrystalRecipe(
        colors=("#0d0500", "#3d1c02", "#a9660c", "#ffc247", "#fff4cf"),
        waves=5,
        frequency=11.0,
        chirp=0.70,
        bands=3.0,
        overlay_waves=9,
        overlay_frequency=18.0,
    ),
    "4": QuasicrystalRecipe(
        colors=("#04040e", "#1b2350", "#4f6bd8", "#9fd0ff", "#ffffff"),
        waves=11,
        frequency=24.0,
        chirp=0.20,
        bands=9.0,
        overlay_waves=7,
        overlay_frequency=35.0,
    ),
    "5": QuasicrystalRecipe(
        colors=("#0a0014", "#2f0a5e", "#7b2ff7", "#f857a6", "#ffe3f4"),
        waves=7,
        frequency=19.0,
        chirp=0.44,
        bands=6.0,
        overlay_waves=13,
        overlay_frequency=28.0,
    ),
}


TRUCHET: dict[str, TruchetRecipe] = {
    "0": TruchetRecipe(
        colors=("#020a10", "#06202c", "#12d7c0", "#3aa0ff", "#c6ff6e"),
        cells=24,
        split=34,
        stroke=0.17,
        jewel="#e9fffb",
    ),
    "1": TruchetRecipe(
        colors=("#160b04", "#33190a", "#d98324", "#f2c14e", "#8c5b2f"),
        cells=18,
        split=22,
        stroke=0.21,
        jewel="#ffe9c2",
    ),
    "2": TruchetRecipe(
        colors=("#08000e", "#1c0430", "#ff2f8e", "#8a2bff", "#25f4ee"),
        cells=30,
        split=46,
        stroke=0.14,
        jewel="#ffffff",
    ),
    "3": TruchetRecipe(
        colors=("#f2e8d5", "#ddcdb0", "#b23a30", "#1f4e5f", "#e0a458"),
        cells=21,
        split=30,
        stroke=0.19,
        jewel="#7a3b2e",
    ),
    "4": TruchetRecipe(
        colors=("#02100a", "#07281a", "#4ade80", "#a3e635", "#f0fdf4"),
        cells=27,
        split=38,
        stroke=0.16,
        jewel="#ecfccb",
    ),
    "5": TruchetRecipe(
        colors=("#040b1e", "#0b1f4d", "#5b8dff", "#b9d0ff", "#ffffff"),
        cells=24,
        split=52,
        stroke=0.13,
        jewel="#dbeafe",
    ),
}


KLEINIAN: dict[str, KleinianRecipe] = {
    "0": KleinianRecipe(
        colors=("#02040c", "#0d2a4d", "#2f8fd6", "#8be0ff", "#fff5cc"),
        mirrors=5,
        snug=0.985,
        inner=0.94,
        iterations=20,
        twist=0.0,
    ),
    "1": KleinianRecipe(
        colors=("#0b0010", "#320a45", "#8f2bbf", "#ff6ad5", "#ffe9fb"),
        mirrors=3,
        snug=0.955,
        inner=0.88,
        iterations=22,
        twist=0.52,
    ),
    "2": KleinianRecipe(
        colors=("#0d0400", "#3a1602", "#b3600b", "#ffb340", "#fff1cd"),
        mirrors=6,
        snug=0.995,
        inner=0.0,
        iterations=18,
        twist=0.26,
    ),
    "3": KleinianRecipe(
        colors=("#00100e", "#04322c", "#0f9b86", "#63ffd8", "#eafff8"),
        mirrors=4,
        snug=0.97,
        inner=0.9,
        iterations=20,
        twist=0.78,
    ),
    "4": KleinianRecipe(
        colors=("#100003", "#3d0413", "#b81237", "#ff7a6b", "#ffe7cf"),
        mirrors=7,
        snug=1.0,
        inner=0.82,
        iterations=17,
        twist=0.13,
    ),
    "5": KleinianRecipe(
        colors=("#07070f", "#1a1c3d", "#5257c4", "#a8b4ff", "#f5f7ff"),
        mirrors=8,
        snug=0.99,
        inner=0.0,
        iterations=16,
        twist=0.39,
    ),
}


OUTLIER_DESIGN_IDS = frozenset(
    {
        "spinner_kleinian",
        "spinner_quasicrystal",
        "spinner_reactor",
        "spinner_strangeloop",
        "spinner_truchet",
    }
)


def _palette(colors: tuple[str, ...]) -> np.ndarray:
    return np.stack([parse_color(color) for color in colors], axis=0)


def _gradient(values: np.ndarray, colors: tuple[str, ...]) -> np.ndarray:
    """Clamped piecewise-linear ramp across the palette."""

    palette = _palette(colors)
    scaled = np.clip(values, 0.0, 1.0).astype(np.float32) * (len(palette) - 1)
    index = np.clip(np.floor(scaled), 0, len(palette) - 2).astype(np.int16)
    fraction = (scaled - index)[..., None]
    return palette[index] * (1.0 - fraction) + palette[index + 1] * fraction


def _hash_cells(*keys: np.ndarray, salt: int) -> np.ndarray:
    """Deterministic integer hash of per-pixel cell coordinates."""

    start = (salt * 2654435761 + 374761393) & 0xFFFFFFFF
    digest = np.full(keys[0].shape, np.uint32(start), dtype=np.uint32)
    for key in keys:
        digest = digest ^ key.astype(np.uint32)
        digest = digest * np.uint32(2246822519)
        digest = digest ^ (digest >> np.uint32(13))
        digest = digest * np.uint32(3266489917)
        digest = digest ^ (digest >> np.uint32(16))
    return digest


def _upsample(field: np.ndarray, width: int, height: int) -> np.ndarray:
    if field.shape == (height, width):
        return field
    source = Image.fromarray(np.ascontiguousarray(field, dtype=np.float32), mode="F")
    resized = source.resize((width, height), Image.Resampling.BICUBIC)
    return np.asarray(resized, dtype=np.float32)


def _blur(field: np.ndarray, radius: float) -> np.ndarray:
    """Blur a 0..1 field.  Pillow only convolves 8-bit images."""

    quantized = Image.fromarray(np.clip(field * 255.0 + 0.5, 0, 255).astype(np.uint8), mode="L")
    softened = quantized.filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(softened, dtype=np.float32) / 255.0


def _vignette(radius: np.ndarray, strength: float = 0.32) -> np.ndarray:
    return 1.0 - strength * smoothstep(0.78, 1.44, radius)


# --------------------------------------------------------------------------
# Gray-Scott reaction diffusion
# --------------------------------------------------------------------------


# The reaction runs on a polar grid, so the pattern closes seamlessly around
# the circle and its regime bands are radial.  Rings are spaced along a mild
# power curve: cells stay close to square over the visible radius, and the
# structure tightens towards the hub without collapsing into slivers.
REACTOR_OUTER = 1.45
REACTOR_CURVE = 1.35
REACTOR_ASPECT = 2.2


def _rings_shift(field: np.ndarray, offset: int) -> np.ndarray:
    """Shift along the radial axis, holding the inner and outer rims."""

    shifted = np.roll(field, offset, axis=0)
    if offset > 0:
        shifted[0] = field[0]
    else:
        shifted[-1] = field[-1]
    return shifted


def _laplacian(field: np.ndarray) -> np.ndarray:
    up = _rings_shift(field, 1)
    down = _rings_shift(field, -1)
    orthogonal = up + down + np.roll(field, 1, axis=1) + np.roll(field, -1, axis=1)
    diagonal = (
        np.roll(up, 1, axis=1)
        + np.roll(up, -1, axis=1)
        + np.roll(down, 1, axis=1)
        + np.roll(down, -1, axis=1)
    )
    return 0.2 * orthogonal + 0.05 * diagonal - field


def _reactor_field(
    rings: int,
    sectors: int,
    recipe: ReactorRecipe,
    rng: np.random.Generator,
) -> np.ndarray:
    depth = ((np.arange(rings, dtype=np.float32) + 0.5) / rings)[:, None]
    theta = ((np.arange(sectors, dtype=np.float32) + 0.5) / sectors * TAU)[None, :]

    # Sweeping the chemistry outwards makes each ring of the wallpaper settle
    # into a different Turing regime: spots, worms, and coral in bands.
    sweep = np.clip(depth + recipe.swirl * np.sin(recipe.arms * theta - 5.0 * depth), 0.0, 1.0)
    feed = (recipe.feed[0] + (recipe.feed[1] - recipe.feed[0]) * sweep).astype(np.float32)
    kill = (recipe.kill[0] + (recipe.kill[1] - recipe.kill[0]) * sweep).astype(np.float32)

    # Seeding the whole grid at once lets the chemistry settle into its regime
    # everywhere; growing it out from a few blobs would need an order of
    # magnitude more iterations to reach the rim.
    grain = _blur(rng.random((rings, sectors), dtype=np.float32), max(1.0, rings / 70.0))
    arms = 0.5 + 0.5 * np.sin(recipe.arms * theta - 7.0 * depth + float(rng.uniform(0.0, TAU)))
    live = smoothstep(0.46, 0.58, grain * (0.55 + 0.60 * arms))

    u = (1.0 - 0.62 * live).astype(np.float32)
    v = (0.30 * live).astype(np.float32)

    # Wide diffusion with a short step: the pattern wavelength then spans
    # enough cells that the square lattice stops showing through as blocky,
    # axis-aligned blobs.
    step = 0.7
    iterations = round((640 + 4.5 * rings) / step)
    for _ in range(iterations):
        reaction = u * v * v
        u += step * (0.42 * _laplacian(u) - reaction + feed * (1.0 - u))
        v += step * (0.21 * _laplacian(v) + reaction - (feed + kill) * v)
        np.clip(u, 0.0, 1.0, out=u)
        np.clip(v, 0.0, 1.0, out=v)
    return v


def _sample_polar(
    field: np.ndarray,
    radius: np.ndarray,
    angle: np.ndarray,
    twist: float,
) -> np.ndarray:
    """Bilinearly read the polar field back onto the image grid."""

    rings, sectors = field.shape
    depth = np.power(np.clip(radius / REACTOR_OUTER, 0.0, 1.0), 1.0 / REACTOR_CURVE)
    spun = np.mod(angle + twist * depth, TAU)
    column = spun / TAU * sectors - 0.5
    depth = np.clip(depth * rings - 0.5, 0.0, rings - 1.001)

    inner = np.floor(depth).astype(np.int32)
    outer = inner + 1
    left = np.mod(np.floor(column), sectors).astype(np.int32)
    right = np.mod(left + 1, sectors)
    along = (depth - inner).astype(np.float32)
    across = (column - np.floor(column)).astype(np.float32)

    near = field[inner, left] * (1.0 - across) + field[inner, right] * across
    far = field[outer, left] * (1.0 - across) + field[outer, right] * across
    return (near * (1.0 - along) + far * along).astype(np.float32)


def _reactor(width: int, height: int, recipe: ReactorRecipe, rng: np.random.Generator) -> Image.Image:
    rings = int(np.clip(round(min(width, height) / 7.0), 48, 300))
    sectors = int(round(rings * REACTOR_ASPECT))
    field = _reactor_field(rings, sectors, recipe, rng)

    low = float(np.quantile(field, 0.30))
    high = float(np.quantile(field, 0.985))
    if high - low < 1e-4:
        high = low + 1e-4

    _, _, image_radius, image_angle = coordinate_grid(width, height)
    dense = _sample_polar(field, image_radius, image_angle, recipe.twist)
    level = smoothstep(low, high, dense)
    membrane = np.exp(-np.square((level - 0.5) / 0.16), dtype=np.float32)

    slope_y, slope_x = np.gradient(level)
    relief = np.clip((slope_x * 0.72 - slope_y * 0.62) * (min(width, height) / 240.0), -1.0, 1.0)

    radius, angle = image_radius, image_angle
    substrate = blend(
        parse_color(recipe.colors[0]),
        parse_color(recipe.colors[1]),
        smoothstep(0.0, 1.3, radius) * 0.85 + 0.15,
    )
    colony = _gradient(level * 0.9 + 0.1 * membrane, recipe.colors[1:])
    colony *= (0.78 + 0.36 * np.clip(relief, 0.0, 1.0) - 0.24 * np.clip(-relief, 0.0, 1.0))[..., None]
    colony = blend(colony, parse_color(recipe.colors[-1]), membrane * 0.34)

    rgb = blend(
        substrate,
        colony,
        smoothstep(0.16, 0.52, level) * smoothstep(0.05, 0.14, radius),
    )
    rgb = blend(rgb, parse_color(recipe.colors[-1]), membrane * 0.16)

    sheen = 0.5 + 0.5 * np.cos(angle * 2.0 - radius * 3.0)
    rgb *= (0.94 + 0.10 * sheen * np.exp(-radius * 0.9))[..., None]
    hub = np.exp(-np.square(radius / 0.09), dtype=np.float32)
    rgb = rgb + parse_color(recipe.colors[2]) * (hub * 0.75)[..., None]
    core = np.exp(-np.square(radius / 0.035), dtype=np.float32)
    rgb = rgb + parse_color(recipe.colors[4]) * (core * 0.95)[..., None]
    return image_from_array(np.clip(rgb * _vignette(radius)[..., None], 0.0, 1.0))


# --------------------------------------------------------------------------
# Chaotic attractor density
# --------------------------------------------------------------------------


def _attractor_step(
    x: np.ndarray,
    y: np.ndarray,
    recipe: AttractorRecipe,
) -> tuple[np.ndarray, np.ndarray]:
    a, b, c, d = recipe.params
    if recipe.kind == "clifford":
        return (
            np.sin(a * y) + c * np.cos(a * x),
            np.sin(b * x) + d * np.cos(b * y),
        )
    return (
        np.sin(a * y) - np.cos(b * x),
        np.sin(c * x) - np.cos(d * y),
    )


def _attractor_extent(recipe: AttractorRecipe) -> float:
    a, b, c, d = recipe.params
    if recipe.kind == "clifford":
        return max(1.0 + abs(c), 1.0 + abs(d))
    return 2.0


def _attractor_density(work: int, recipe: AttractorRecipe, rng: np.random.Generator) -> np.ndarray:
    count = int(np.clip(round(work * work * 0.10), 12_000, 260_000))
    x = rng.uniform(-1.0, 1.0, count).astype(np.float32)
    y = rng.uniform(-1.0, 1.0, count).astype(np.float32)
    for _ in range(24):
        x, y = _attractor_step(x, y, recipe)

    scale = np.float32(0.455 * work / _attractor_extent(recipe))
    center = np.float32(work / 2.0)
    density = np.zeros(work * work, dtype=np.float32)
    for _ in range(recipe.steps):
        x, y = _attractor_step(x, y, recipe)
        column = np.rint(x * scale + center).astype(np.int32)
        row = np.rint(y * scale + center).astype(np.int32)
        inside = (column >= 0) & (column < work) & (row >= 0) & (row < work)
        density += np.bincount(
            row[inside] * work + column[inside], minlength=work * work
        ).astype(np.float32)
    return density.reshape(work, work)


def _symmetrize(field: np.ndarray, folds: int) -> np.ndarray:
    if folds < 2:
        return field
    source = Image.fromarray(np.ascontiguousarray(field, dtype=np.float32), mode="F")
    total = field.copy()
    for fold in range(1, folds):
        rotated = source.rotate(360.0 * fold / folds, resample=Image.Resampling.BILINEAR)
        total += np.asarray(rotated, dtype=np.float32)
    return total / folds


def _strangeloop(
    width: int,
    height: int,
    recipe: AttractorRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    work = int(min(min(width, height), 1400))
    density = _symmetrize(_attractor_density(work, recipe, rng), recipe.symmetry)

    lit = density[density > 0.0]
    peak = float(np.quantile(lit, 0.9995)) if lit.size else 1.0
    peak = max(peak, 1e-3)
    tone = np.log1p(density * (recipe.gain / peak)) / math.log1p(recipe.gain)
    tone = np.clip(tone, 0.0, 1.0).astype(np.float32)

    bloom = _blur(tone, max(1.0, work / 90.0))

    # Bicubic enlargement overshoots at hard edges, so re-clamp before the
    # tone curve reads these as 0..1.
    tone = np.clip(_upsample(tone, width, height), 0.0, 1.0)
    bloom = np.clip(_upsample(bloom, width, height), 0.0, 1.0)

    _, _, radius, angle = coordinate_grid(width, height)
    rgb = blend(
        parse_color(recipe.colors[0]),
        parse_color(recipe.colors[1]),
        smoothstep(0.0, 1.35, radius) * 0.85,
    )
    dust = _gradient(np.power(tone, 0.62, dtype=np.float32), recipe.colors[1:])
    rgb = rgb + dust * np.clip(tone * 1.25, 0.0, 1.0)[..., None]
    rgb += parse_color(recipe.colors[3]) * (bloom * 0.42)[..., None]

    spin = 0.5 + 0.5 * np.sin(recipe.symmetry * angle + 3.0 * radius)
    rgb *= (0.96 + 0.07 * spin)[..., None]
    hub = np.exp(-np.square(radius / 0.10), dtype=np.float32)
    rgb = rgb + parse_color(recipe.colors[3]) * (hub * 0.55)[..., None]
    core = np.exp(-np.square(radius / 0.045), dtype=np.float32)
    rgb = rgb + parse_color(recipe.colors[4]) * (core * 0.95)[..., None]
    return image_from_array(np.clip(rgb * _vignette(radius, 0.30)[..., None], 0.0, 1.0))


# --------------------------------------------------------------------------
# Quasiperiodic wave interference
# --------------------------------------------------------------------------


def _interference(
    x: np.ndarray,
    y: np.ndarray,
    waves: int,
    frequency: float,
    phase: float,
) -> np.ndarray:
    total = np.zeros(x.shape, dtype=np.float32)
    for index in range(waves):
        theta = math.pi * index / waves
        total += np.cos(frequency * (x * math.cos(theta) + y * math.sin(theta)) + phase)
    return total / waves


def _quasicrystal_base(
    width: int,
    height: int,
    recipe: QuasicrystalRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    x, y, radius, angle = coordinate_grid(width, height)
    phase = float(rng.uniform(0.0, TAU))
    stretch = (1.0 + recipe.chirp * np.power(radius, 1.35, dtype=np.float32)).astype(np.float32)
    value = _interference(x * stretch, y * stretch, recipe.waves, recipe.frequency, phase)

    # Terracing the interference turns a soft blob field into flat plateaus
    # separated by sharp risers, which is what makes the quasiperiodic
    # symmetry legible.
    stepped = value * recipe.bands
    plateau = np.floor(stepped)
    fraction = (stepped - plateau).astype(np.float32)
    riser = smoothstep(0.68, 0.97, fraction)
    level = np.clip(0.5 + 0.5 * (plateau + riser) / recipe.bands, 0.0, 1.0)

    rgb = _gradient(level, recipe.colors)
    seam = np.exp(-np.square((fraction - 0.84) / 0.075), dtype=np.float32)
    rgb = blend(rgb, parse_color(recipe.colors[-1]), seam * 0.45)
    rgb *= (0.84 + 0.26 * (0.5 + 0.5 * value) + 0.07 * np.cos(angle * recipe.waves))[..., None]

    halo = np.exp(-np.square(radius / 0.22), dtype=np.float32)
    rgb = blend(rgb, parse_color(recipe.colors[-2]), halo * 0.30)
    return image_from_array(np.clip(rgb * _vignette(radius, 0.30)[..., None], 0.0, 1.0))


def _quasicrystal_moire(
    width: int,
    height: int,
    recipe: QuasicrystalRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    x, y, radius, _ = coordinate_grid(width, height)
    phase = float(rng.uniform(0.0, TAU))
    value = _interference(x, y, recipe.overlay_waves, recipe.overlay_frequency, phase)

    fringe = np.exp(-np.square(value / 0.055), dtype=np.float32)
    fringe += 0.45 * np.exp(-np.square((np.abs(value) - 0.42) / 0.05), dtype=np.float32)
    alpha = np.clip(fringe, 0.0, 1.0) * (0.30 + 0.55 * smoothstep(0.06, 0.95, radius))
    alpha *= 1.0 - smoothstep(1.05, 1.45, radius)

    tint = blend(
        parse_color(recipe.colors[-1]),
        parse_color(recipe.colors[2]),
        smoothstep(0.1, 1.2, radius),
    )
    rgba = np.concatenate((tint, alpha[..., None]), axis=2)
    return image_from_array(np.clip(rgba, 0.0, 1.0), mode="RGBA")


# --------------------------------------------------------------------------
# Log-polar Truchet weave
# --------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class _TruchetCells:
    fraction_u: np.ndarray
    fraction_v: np.ndarray
    digest: np.ndarray
    cell_pixels: np.ndarray
    radius: np.ndarray
    angle: np.ndarray
    hub: float


def _truchet_cells(width: int, height: int, recipe: TruchetRecipe) -> _TruchetCells:
    """Build a log-polar grid whose cells stay square, then subdivide it.

    Cells shrink towards the centre, so subdivision stops once a cell is too
    small to hold a legible arc; that keeps the weave from turning to noise at
    any output size.
    """

    _, _, radius, angle = coordinate_grid(width, height)
    span = min(width, height) / 2.0
    safe_radius = np.maximum(radius, np.float32(1e-3))
    cells = recipe.cells

    u = (np.mod(angle, TAU) / TAU).astype(np.float32) * cells
    v = np.log(safe_radius, dtype=np.float32) * (cells / TAU)
    subdivision = np.ones(radius.shape, dtype=np.float32)
    pixels = (safe_radius * span * TAU / cells).astype(np.float32)

    digest = _hash_cells(
        np.mod(np.floor(u), cells).astype(np.int32),
        np.floor(v).astype(np.int32),
        salt=cells,
    )
    for level in range(1, 3):
        splitting = ((digest % np.uint32(100)) < np.uint32(recipe.split)) & (pixels > 30.0)
        u = np.where(splitting, u * 2.0, u)
        v = np.where(splitting, v * 2.0, v)
        subdivision = np.where(splitting, subdivision * 2.0, subdivision)
        pixels = np.where(splitting, pixels * 0.5, pixels)
        cells *= 2
        digest = np.where(
            splitting,
            _hash_cells(
                np.mod(np.floor(u), cells).astype(np.int32),
                np.floor(v).astype(np.int32),
                salt=cells + level,
            ),
            digest,
        )

    return _TruchetCells(
        fraction_u=(u - np.floor(u)).astype(np.float32),
        fraction_v=(v - np.floor(v)).astype(np.float32),
        digest=digest,
        cell_pixels=np.maximum(pixels, np.float32(1e-3)),
        radius=radius,
        angle=angle,
        hub=float(np.clip(9.0 * recipe.cells / (span * TAU), 0.055, 0.3)),
    )


def _truchet_distance(cells: _TruchetCells) -> np.ndarray:
    fu, fv = cells.fraction_u, cells.fraction_v
    first = np.abs(np.hypot(fu, fv) - 0.5)
    second = np.abs(np.hypot(1.0 - fu, 1.0 - fv) - 0.5)
    third = np.abs(np.hypot(1.0 - fu, fv) - 0.5)
    fourth = np.abs(np.hypot(fu, 1.0 - fv) - 0.5)
    flipped = (cells.digest & np.uint32(1)).astype(bool)
    return np.where(flipped, np.minimum(third, fourth), np.minimum(first, second)).astype(np.float32)


def _truchet_weave(
    width: int,
    height: int,
    recipe: TruchetRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    cells = _truchet_cells(width, height, recipe)
    distance = _truchet_distance(cells)
    radius = cells.radius

    softness = np.clip(1.4 / cells.cell_pixels, 0.004, 0.34).astype(np.float32)
    half = np.float32(recipe.stroke)
    ink = np.clip((half - distance) / softness + 0.5, 0.0, 1.0).astype(np.float32)
    tube = np.sqrt(np.clip(1.0 - np.square(distance / half), 0.0, 1.0), dtype=np.float32)

    background = blend(
        parse_color(recipe.colors[0]),
        parse_color(recipe.colors[1]),
        smoothstep(0.0, 1.4, radius) * 0.9,
    )
    strand = palette_image(((cells.digest >> np.uint32(8)) % np.uint32(3)).astype(np.int32), recipe.colors[2:])
    lit = strand * (0.58 + 0.52 * tube)[..., None]
    lit = blend(lit, np.ones(3, dtype=np.float32), np.power(tube, 6.0, dtype=np.float32) * 0.30)

    glow = np.exp(-np.square(distance / (half * 2.7)), dtype=np.float32)
    rgb = background + strand * (glow * 0.22)[..., None]
    rgb = blend(rgb, lit, ink * smoothstep(cells.hub, cells.hub * 2.0, radius))

    hub = 1.0 - smoothstep(cells.hub * 0.55, cells.hub * 1.35, radius)
    rgb = blend(rgb, parse_color(recipe.colors[2]), hub)
    rgb = blend(
        rgb,
        np.ones(3, dtype=np.float32),
        np.exp(-np.square(radius / (cells.hub * 0.7)), dtype=np.float32) * 0.65,
    )
    phase = float(rng.uniform(0.0, TAU))
    rgb *= (0.95 + 0.06 * np.cos(3.0 * cells.angle + phase))[..., None]
    return image_from_array(np.clip(rgb * _vignette(radius, 0.34)[..., None], 0.0, 1.0))


def _truchet_jewels(
    width: int,
    height: int,
    recipe: TruchetRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    cells = _truchet_cells(width, height, recipe)
    fu, fv = cells.fraction_u, cells.fraction_v
    node = np.minimum(np.hypot(fu - 0.5, fv), np.hypot(fu, fv - 0.5)).astype(np.float32)

    spark = np.exp(-np.square(node / 0.13), dtype=np.float32)
    chosen = ((cells.digest >> np.uint32(16)) % np.uint32(5)) < np.uint32(2)
    alpha = spark * chosen * (1.0 - smoothstep(1.02, 1.42, cells.radius))
    alpha *= smoothstep(cells.hub, cells.hub * 2.2, cells.radius)
    alpha = np.clip(alpha * 0.85, 0.0, 1.0)

    phase = float(rng.uniform(0.0, TAU))
    warm = 0.5 + 0.5 * np.sin(4.0 * cells.angle + 6.0 * cells.radius + phase)
    tint = blend(parse_color(recipe.jewel), parse_color(recipe.colors[4]), warm * 0.55)
    rgba = np.concatenate((tint, alpha[..., None]), axis=2)
    return image_from_array(np.clip(rgba, 0.0, 1.0), mode="RGBA")


# --------------------------------------------------------------------------
# Circle-inversion limit set
# --------------------------------------------------------------------------


def _mirror_circles(recipe: KleinianRecipe) -> list[tuple[float, float, float]]:
    count = recipe.mirrors
    ring = 0.985 / (1.0 + math.sin(math.pi / count))
    mirror_radius = ring * math.sin(math.pi / count) * recipe.snug
    circles = []
    for index in range(count):
        theta = TAU * index / count + recipe.twist
        circles.append((ring * math.cos(theta), ring * math.sin(theta), mirror_radius))
    if recipe.inner > 0.0:
        circles.append((0.0, 0.0, (ring - mirror_radius) * recipe.inner))
    return circles


def _kleinian(
    width: int,
    height: int,
    recipe: KleinianRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    x, y, radius, angle = coordinate_grid(width, height)
    circles = _mirror_circles(recipe)

    position_x = x.copy()
    position_y = y.copy()
    depth = np.zeros(x.shape, dtype=np.int16)
    magnification = np.ones(x.shape, dtype=np.float32)
    epsilon = np.float32(1e-7)

    for _ in range(recipe.iterations):
        outside = position_x * position_x + position_y * position_y > 1.0
        if outside.any():
            inverse = 1.0 / np.maximum(position_x * position_x + position_y * position_y, epsilon)
            position_x = np.where(outside, position_x * inverse, position_x)
            position_y = np.where(outside, position_y * inverse, position_y)
            magnification = np.where(outside, magnification * inverse, magnification)
            depth += outside
        for center_x, center_y, mirror_radius in circles:
            offset_x = position_x - center_x
            offset_y = position_y - center_y
            square = offset_x * offset_x + offset_y * offset_y
            inside = square < mirror_radius * mirror_radius
            if not inside.any():
                continue
            factor = (mirror_radius * mirror_radius) / np.maximum(square, epsilon)
            position_x = np.where(inside, center_x + offset_x * factor, position_x)
            position_y = np.where(inside, center_y + offset_y * factor, position_y)
            magnification = np.where(inside, magnification * factor, magnification)
            depth += inside
        np.clip(magnification, 0.0, 1e12, out=magnification)

    boundary = np.abs(np.hypot(position_x, position_y) - 1.0)
    for center_x, center_y, mirror_radius in circles:
        boundary = np.minimum(
            boundary,
            np.abs(np.hypot(position_x - center_x, position_y - center_y) - mirror_radius),
        )
    estimate = boundary / np.maximum(magnification, epsilon)
    line_width = np.float32(2.4 / (min(width, height) / 2.0))
    hairline = np.exp(-np.square(estimate / line_width), dtype=np.float32)

    # Each bubble is one tile of the group, so shading it by where the orbit
    # finally landed gives every nested copy the same interior lighting.
    settled = np.hypot(position_x, position_y).astype(np.float32)
    settled_angle = np.arctan2(position_y, position_x).astype(np.float32)
    shell = palette_image(np.mod(depth, len(recipe.colors) - 1).astype(np.int32), recipe.colors[1:])
    # Shading on a multiple of the mirror count keeps the whole design exactly
    # n-fold symmetric, so it reads the same however far the layer has turned.
    interior = 0.34 + 0.62 * smoothstep(0.05, 0.95, settled)
    interior += 0.16 * (0.5 + 0.5 * np.sin(recipe.mirrors * settled_angle + 7.0 * settled))
    rgb = shell * interior[..., None]

    ground = blend(
        parse_color(recipe.colors[0]),
        parse_color(recipe.colors[1]),
        smoothstep(0.0, 1.3, radius) * 0.7,
    )
    rgb = np.where((depth == 0)[..., None], ground, rgb)
    rgb = blend(rgb, parse_color(recipe.colors[-1]), hairline * 0.9)

    phase = float(rng.uniform(0.0, TAU))
    swirl = 0.5 + 0.5 * np.sin(recipe.mirrors * angle + 4.0 * radius + phase)
    rgb *= (0.90 + 0.14 * swirl)[..., None]
    rgb = blend(
        rgb,
        np.ones(3, dtype=np.float32),
        np.exp(-np.square(radius / 0.035), dtype=np.float32) * 0.5,
    )
    return image_from_array(np.clip(rgb * _vignette(radius, 0.26)[..., None], 0.0, 1.0))


# --------------------------------------------------------------------------


def render_outlier(
    design_id: str,
    variant: str,
    width: int,
    height: int,
    layer_index: int,
    seed: int,
) -> Image.Image:
    """Render one layer of an experimental spinner family.

    Layer geometry comes from the variant recipe, so the two-layer families
    stay registered with each other; the generator only supplies phase jitter.
    """

    rng = rng_for(seed, "outliers", design_id, variant, layer_index)
    if design_id == "spinner_reactor":
        return _reactor(width, height, REACTOR[variant], rng)
    if design_id == "spinner_strangeloop":
        return _strangeloop(width, height, ATTRACTOR[variant], rng)
    if design_id == "spinner_quasicrystal":
        recipe = QUASICRYSTAL[variant]
        if layer_index == 0:
            return _quasicrystal_base(width, height, recipe, rng)
        return _quasicrystal_moire(width, height, recipe, rng)
    if design_id == "spinner_truchet":
        recipe = TRUCHET[variant]
        if layer_index == 0:
            return _truchet_weave(width, height, recipe, rng)
        return _truchet_jewels(width, height, recipe, rng)
    if design_id == "spinner_kleinian":
        return _kleinian(width, height, KLEINIAN[variant], rng)
    raise ValueError(f"no outlier renderer registered for {design_id}")
