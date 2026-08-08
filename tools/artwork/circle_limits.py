"""Hyperbolic circle-limit patterns built from Poincare reflections.

The renderer folds the unit disk into one fundamental triangle of a regular
``{p, q}`` tiling.  Every fold is a reflection in either a diameter or a
circle orthogonal to the disk, so detail contracts conformally towards the
boundary instead of being faked with concentric scaling.
"""

from __future__ import annotations

from dataclasses import dataclass
import math

import numpy as np
from PIL import Image

from .core import blend, coordinate_grid, image_from_array, parse_color, smoothstep


TAU = math.tau


@dataclass(frozen=True, slots=True)
class CircleLimitRecipe:
    """One regular hyperbolic tiling and its illustrated color treatment."""

    p: int
    q: int
    colors: tuple[str, str, str, str]
    ink: str
    paper: str
    accent: str
    rim: float = 0.925
    reflections: int = 28


CIRCLE_LIMITS: dict[str, CircleLimitRecipe] = {
    "0": CircleLimitRecipe(
        p=6,
        q=4,
        colors=("#153448", "#2f7f83", "#d5b36a", "#f1e4bf"),
        ink="#10191b",
        paper="#d7c9a3",
        accent="#f7edce",
    ),
    "1": CircleLimitRecipe(
        p=8,
        q=3,
        colors=("#721817", "#c2442f", "#e7a348", "#f3d7a0"),
        ink="#291512",
        paper="#ead8ad",
        accent="#fff0bd",
    ),
    "2": CircleLimitRecipe(
        p=5,
        q=4,
        colors=("#173b2a", "#477d43", "#a1a94b", "#e0d5a0"),
        ink="#132018",
        paper="#cbbf91",
        accent="#f4e9b4",
    ),
    "3": CircleLimitRecipe(
        p=7,
        q=3,
        colors=("#241a48", "#534185", "#9f638c", "#dcad8f"),
        ink="#171329",
        paper="#c9b8a0",
        accent="#f1d8b5",
    ),
    "4": CircleLimitRecipe(
        p=4,
        q=5,
        colors=("#232323", "#55534e", "#999184", "#ddd0b8"),
        ink="#11100e",
        paper="#c8bda9",
        accent="#f0e5cf",
    ),
    "5": CircleLimitRecipe(
        p=10,
        q=3,
        colors=("#102e4f", "#23658b", "#dc6b3f", "#e9b86c"),
        ink="#101a24",
        paper="#c9c3ae",
        accent="#f6dfad",
    ),
}


CIRCLE_LIMIT_DESIGN_IDS = frozenset({"spinner_circlelimit"})


def _mirror_geometry(recipe: CircleLimitRecipe) -> tuple[float, float, float]:
    """Return wedge angle, center and radius of the curved triangle mirror."""

    wedge = math.pi / recipe.p
    vertex_angle = math.pi / recipe.q
    # In the right hyperbolic fundamental triangle, the center-to-edge side
    # has cosh(length) = cos(vertex angle) / sin(center angle).
    cosh_edge = math.cos(vertex_angle) / math.sin(wedge)
    if cosh_edge <= 1.0:
        raise ValueError(f"{{{recipe.p}, {recipe.q}}} is not a hyperbolic tiling")
    poincare_edge = math.sqrt((cosh_edge - 1.0) / (cosh_edge + 1.0))
    center = (1.0 + poincare_edge * poincare_edge) / (2.0 * poincare_edge)
    radius = (1.0 - poincare_edge * poincare_edge) / (2.0 * poincare_edge)
    return wedge, center, radius


def _fold_disk(
    x: np.ndarray,
    y: np.ndarray,
    disk: np.ndarray,
    recipe: CircleLimitRecipe,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Fold disk coordinates into the tiling's fundamental triangle."""

    wedge, mirror_center, mirror_radius = _mirror_geometry(recipe)
    center_x = mirror_center * math.cos(wedge)
    center_y = mirror_center * math.sin(wedge)
    radius_squared = np.float32(mirror_radius * mirror_radius)

    folded_x = x.astype(np.float32, copy=True)
    folded_y = y.astype(np.float32, copy=True)
    word = np.zeros(x.shape, dtype=np.int32)
    depth = np.zeros(x.shape, dtype=np.int16)
    mirrored = np.zeros(x.shape, dtype=bool)

    for step in range(recipe.reflections):
        radius = np.hypot(folded_x, folded_y, dtype=np.float32)
        angle = np.mod(np.arctan2(folded_y, folded_x), TAU)
        sector = np.floor(angle / wedge).astype(np.int16)
        remainder = angle - sector.astype(np.float32) * np.float32(wedge)
        odd = np.bitwise_and(sector, 1).astype(bool)
        local_angle = np.where(odd, wedge - remainder, remainder)
        mirrored ^= odd & disk
        folded_x = radius * np.cos(local_angle)
        folded_y = radius * np.sin(local_angle)

        dx = folded_x - center_x
        dy = folded_y - center_y
        distance_squared = dx * dx + dy * dy
        active = disk & (distance_squared < radius_squared * np.float32(0.999999))
        if not np.any(active):
            break

        # The word records which copies of the central polygon a point crossed
        # on its way home.  It supplies a deterministic four-coloring without
        # changing the reflection geometry.
        next_word = word * np.int32(13) + sector.astype(np.int32) * np.int32(5)
        next_word += np.int32(step * 7 + 1)
        word = np.where(active, next_word, word)
        depth += active
        mirrored ^= active

        factor = radius_squared / np.maximum(distance_squared, np.float32(1e-12))
        folded_x = np.where(active, center_x + dx * factor, folded_x)
        folded_y = np.where(active, center_y + dy * factor, folded_y)

    radius = np.hypot(folded_x, folded_y, dtype=np.float32)
    angle = np.mod(np.arctan2(folded_y, folded_x), TAU)
    sector = np.floor(angle / wedge).astype(np.int16)
    remainder = angle - sector.astype(np.float32) * np.float32(wedge)
    odd = np.bitwise_and(sector, 1).astype(bool)
    local_angle = np.where(odd, wedge - remainder, remainder)
    mirrored ^= odd & disk
    return radius, local_angle, word, depth, mirrored


def _render_full_size(
    width: int,
    height: int,
    recipe: CircleLimitRecipe,
    rng: np.random.Generator,
) -> Image.Image:
    x, y, radius, angle = coordinate_grid(width, height)
    normalized_x = x / recipe.rim
    normalized_y = y / recipe.rim
    normalized_radius = radius / recipe.rim
    disk = normalized_radius < 0.9995

    local_radius, local_angle, word, depth, mirrored = _fold_disk(
        normalized_x,
        normalized_y,
        disk,
        recipe,
    )
    wedge, mirror_center, _ = _mirror_geometry(recipe)

    # The curved mirror's inner intersection gives the triangle's radial edge
    # at every local angle.  Normalizing by it turns the triangle into a handy
    # 0..1 illustration space while keeping all boundaries exactly registered.
    projection = mirror_center * np.cos(local_angle - wedge)
    edge_radius = projection - np.sqrt(np.maximum(projection * projection - 1.0, 0.0))
    radial = np.clip(local_radius / np.maximum(edge_radius, 1e-6), 0.0, 1.0)
    angular = np.clip(local_angle / wedge, 0.0, 1.0)
    figure_y = np.where(mirrored, 1.0 - angular, angular)

    palette = np.stack([parse_color(color) for color in recipe.colors], axis=0)
    labels = np.mod(word + depth.astype(np.int32) * 2, len(palette))
    rgb = palette[labels]

    # Each reflected triangle carries half of an interlocking winged-fish
    # motif.  Neighbouring halves meet at mirror lines, producing whole figures
    # rather than isolated decals, while the tessellation does the shrinking.
    shoulder = 0.50 + 0.17 * np.sin(math.pi * radial)
    wing = smoothstep(-0.025, 0.035, shoulder - figure_y)
    neighboring = palette[np.mod(labels + 1 + (depth & 1), len(palette))]
    rgb = blend(rgb, neighboring, wing * (0.28 + 0.17 * radial))

    belly_curve = 0.21 + 0.17 * np.sin(math.pi * radial) ** 2
    belly = smoothstep(0.025, -0.025, np.abs(figure_y - belly_curve) - 0.055)
    rgb = blend(rgb, parse_color(recipe.accent), belly * 0.34)

    feather_phase = radial * (3.0 + 0.5 * (depth & 1)) + figure_y * 0.72
    feather_distance = np.abs(np.mod(feather_phase, 1.0) - 0.5)
    feathers = 1.0 - smoothstep(0.035, 0.085, feather_distance)
    feathers *= smoothstep(0.34, 0.58, figure_y) * smoothstep(0.96, 0.68, figure_y)

    spine = np.abs(figure_y - shoulder)
    linework = 1.0 - smoothstep(0.012, 0.032, spine)
    linework = np.maximum(linework, feathers * 0.68)

    eye_x = (radial - 0.76) / 0.055
    eye_y = (figure_y - 0.205) / 0.085
    eye = 1.0 - smoothstep(0.68, 1.18, eye_x * eye_x + eye_y * eye_y)
    eye_glint = 1.0 - smoothstep(
        0.22,
        0.72,
        ((radial - 0.775) / 0.018) ** 2 + ((figure_y - 0.185) / 0.027) ** 2,
    )

    angular_edge = np.minimum(angular, 1.0 - angular) * np.maximum(radial, 0.11)
    triangle_edge = np.minimum(angular_edge, 1.0 - radial)
    seams = 1.0 - smoothstep(0.010, 0.028, triangle_edge)
    ink = parse_color(recipe.ink)
    rgb = blend(rgb, ink, np.maximum(seams * 0.88, linework * 0.72))
    rgb = blend(rgb, ink, eye * 0.96)
    rgb = blend(rgb, parse_color(recipe.accent), eye_glint * eye)

    # A restrained print-like surface keeps the exact geometry from looking
    # sterile.  The seed only moves this grain; it never changes the tiling.
    grain_phase = rng.uniform(0.0, TAU, 3)
    grain = (
        np.sin(x * 131.0 + y * 47.0 + grain_phase[0])
        + np.sin(x * 59.0 - y * 113.0 + grain_phase[1])
        + np.sin((x + y) * 173.0 + grain_phase[2])
    ) / 3.0
    illumination = 0.92 + 0.08 * (0.5 + 0.5 * np.cos(math.pi * figure_y))
    illumination *= 1.0 + grain * 0.018
    rgb = np.clip(rgb * illumination[..., None], 0.0, 1.0)

    paper = parse_color(recipe.paper)
    paper_noise = 0.955 + 0.025 * grain
    outside = np.clip(paper * paper_noise[..., None], 0.0, 1.0)
    rgb = np.where(disk[..., None], rgb, outside)

    # Close the infinite packing with a crisp double rule.  A narrow fade just
    # inside it suppresses sub-pixel tiles that no raster can resolve.
    unresolved = smoothstep(0.982, 0.999, normalized_radius)
    rgb = blend(rgb, ink, unresolved * disk * 0.32)
    border_distance = np.abs(radius - recipe.rim)
    border = 1.0 - smoothstep(0.0025, 0.0085, border_distance)
    inner_rule = 1.0 - smoothstep(0.0015, 0.0045, np.abs(radius - recipe.rim * 0.977))
    rgb = blend(rgb, ink, np.maximum(border * 0.96, inner_rule * 0.56))

    # A tiny central medallion resolves the angle singularity into a deliberate
    # rosette, as in a carved or block-printed ornament.
    core = 1.0 - smoothstep(0.0, 0.018, radius)
    core_star = 0.5 + 0.5 * np.cos(recipe.p * angle)
    core_color = blend(ink, parse_color(recipe.accent), core_star[..., None] * 0.58)
    rgb = blend(rgb, core_color, core)
    return image_from_array(rgb)


def render_circle_limit(
    variant: str,
    width: int,
    height: int,
    rng: np.random.Generator,
) -> Image.Image:
    """Render one of the six locally-authored hyperbolic circle patterns."""

    recipe = CIRCLE_LIMITS[variant]
    if min(width, height) < 512:
        image = _render_full_size(width * 2, height * 2, recipe, rng)
        return image.resize((width, height), Image.Resampling.LANCZOS)
    return _render_full_size(width, height, recipe, rng)
