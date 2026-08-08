"""Procedural renderers for Gyre's spinner families."""

from __future__ import annotations

import math
import re
from typing import Callable

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from .catalog import LayerSpec, RemixSpec
from .core import (
    blend,
    coordinate_grid,
    hsv_image,
    image_from_array,
    palette_image,
    parse_color,
    rng_for,
    smoothstep,
)
from .effects import apply_effect
from .spirals import SPIRAL_DESIGN_IDS, render_spiral
from .outliers import OUTLIER_DESIGN_IDS, render_outlier
from .palettes import (
    BEACHBALL,
    BRIGHT_BEACHBALL,
    FIBER_ORBIT,
    HOOP_LAYER_COLORS,
    HOOPS_BACKGROUNDS,
    HUD,
    RAINBOW_SPOKES,
    STAMPED,
    STREAKED,
    SUNBURST,
    TEARDROP_BLUE,
    TEARDROPS,
    WHIRLPOOL_DUO,
    WOBBLY,
)


TAU = math.tau

# Catalogue previews use a centred crop of the native layer stack. Renderers
# below create the unframed artwork; the exporter applies these scales only
# when it creates flattened wallpapers.
COMPOSITE_SCALES: dict[str, float] = {
    "spinner_7": 2.0,
    "spinner_11": 20.0 / 7.0,
    "spinner_17": 2.0,
    "spinner_18": 2.0,
    "spinner_23": 2.5,
    "spinner_23f": 2.5,
    "spinner_25b": 2.5,
    "spinner_28c": 4.0,
    "spinner_30c": 2.0,
    "spinner_31c": 1.25,
    "spinner_37b": 2.5,
    "spinner_39b": 10.0 / 3.0,
    "spinner_36": 2.5,
}

# Two shared Hooli-Hoops overlays use trimmed canvases rather than square ones.
NATIVE_LAYER_SIZES: dict[str, tuple[int, int]] = {
    "9a5a8e63000819a376a12976b1ac2533b19b61ff71192868e4048c3505395673.webp": (2596, 2413),
    "cb820c19e113d475abd9786c9dddc47d3ffbc8293631a97ff8bce9bee3f9d987.webp": (2596, 2532),
}


def _preview_fitted_grid(
    width: int,
    height: int,
    design_id: str,
    preview_center: tuple[float, float],
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Map a preview-calibrated equation onto its native layer canvas."""

    scale = COMPOSITE_SCALES.get(design_id, 1.0)
    source_center = (
        0.5 + (preview_center[0] - 0.5) / scale,
        0.5 + (preview_center[1] - 0.5) / scale,
    )
    x, y, radius, angle = coordinate_grid(width, height, center=source_center)
    return x * scale, y * scale, radius * scale, angle


def variant_number(remix_id: str) -> str:
    match = re.search(r"_(\d+)$", remix_id)
    if not match:
        raise ValueError(f"remix has no numeric variant: {remix_id}")
    return match.group(1)


def effect_name(remix_id: str) -> str:
    try:
        return remix_id.split("_fx_", 1)[1]
    except IndexError as exc:
        raise ValueError(f"remix has no effect suffix: {remix_id}") from exc


def layer_dimensions(layer: LayerSpec, size: int) -> tuple[int, int]:
    native = NATIVE_LAYER_SIZES.get(layer.source_key.split("/")[-1], (2600, 2600))
    if size == 2600:
        return native
    scale = size / 2600.0
    return max(1, round(native[0] * scale)), max(1, round(native[1] * scale))


def _boundary_shadow(rgb: np.ndarray, fraction: np.ndarray, strength: float = 0.22) -> np.ndarray:
    distance = np.minimum(fraction, 1.0 - fraction)
    shadow = np.exp(-distance * 32.0, dtype=np.float32) * strength
    return np.clip(rgb * (1.0 - shadow[..., None]), 0.0, 1.0)


def _rainbow_value(hue: np.ndarray) -> np.ndarray:
    wrapped = np.mod(hue, 1.0)
    return np.interp(
        wrapped,
        np.asarray([0.0, 1 / 6, 2 / 6, 3 / 6, 4 / 6, 5 / 6, 1.0], dtype=np.float32),
        np.asarray([0.90, 1.00, 0.69, 0.91, 0.62, 0.61, 0.90], dtype=np.float32),
    ).astype(np.float32)


def _rainbow_whirlpool(width: int, height: int) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.50125, 0.50000))
    knots = np.asarray([0.015, 0.030, 0.050, 0.075, 0.100, 0.150, 0.220, 0.320, 0.450, 0.600, 0.800, 1.000, 1.200, 1.400], dtype=np.float32)
    radial_phase = np.interp(
        radius,
        knots,
        np.asarray([0.03824, 0.02638, 0.00732, -0.01805, -0.04296, -0.09782, -0.19848, -0.35534, -0.49273, -0.67612, -0.94871, -1.21374, -1.48873, -1.74489], dtype=np.float32),
    ).astype(np.float32)
    hue = angle / TAU + radial_phase
    value = _rainbow_value(hue) * (0.98 - 0.06 * np.exp(-((radius - 0.3) / 0.22) ** 2))
    rgb = hsv_image(hue, 0.85, value)
    return image_from_array(rgb)


def _dry_marker_whirlpool(
    width: int,
    height: int,
    rng: np.random.Generator,
) -> Image.Image:
    """Paint the marker remix as tapered ribbons in the spiral vector field."""

    shorter_side = min(width, height)
    if shorter_side < 512:
        enlargement = 512.0 / shorter_side
        working_size = (round(width * enlargement), round(height * enlargement))
        painted = _dry_marker_whirlpool(*working_size, rng)
        return painted.resize((width, height), Image.Resampling.LANCZOS)

    palette = (
        (255, 5, 22),
        (243, 96, 22),
        (250, 218, 13),
        (4, 170, 78),
        (2, 175, 239),
        (31, 73, 164),
        (113, 54, 153),
        (242, 2, 76),
    )
    band_count = len(palette)
    scale = min(width, height) / 2.0
    center = np.asarray((width / 2.0, height / 2.0), dtype=np.float32)
    sample_count = max(240, round(min(width, height) / 1.2))
    radius = np.linspace(0.035, 1.52, sample_count, dtype=np.float32)
    knots = np.asarray(
        [0.015, 0.030, 0.050, 0.075, 0.100, 0.150, 0.220, 0.320, 0.450, 0.600, 0.800, 1.000, 1.200, 1.400],
        dtype=np.float32,
    )
    radial_phase = np.interp(
        radius,
        knots,
        np.asarray(
            [0.03824, 0.02638, 0.00732, -0.01805, -0.04296, -0.09782, -0.19848, -0.35534, -0.49273, -0.67612, -0.94871, -1.21374, -1.48873, -1.74489],
            dtype=np.float32,
        ),
    ).astype(np.float32)
    radial_slope = np.gradient(radial_phase, radius).astype(np.float32)
    phase_gradient = np.hypot(radial_slope, 1.0 / (TAU * radius))
    band_spacing = np.clip(scale / (band_count * phase_gradient), 2.0, scale * 0.15).astype(np.float32)

    image = Image.new("RGB", (width, height), "black")
    draw = ImageDraw.Draw(image)

    def profile(nodes: int, amplitude: float = 1.0) -> np.ndarray:
        positions = np.linspace(0.0, 1.0, nodes, dtype=np.float32)
        values = rng.normal(0.0, amplitude, nodes).astype(np.float32)
        return np.interp(
            np.linspace(0.0, 1.0, sample_count, dtype=np.float32),
            positions,
            values,
        ).astype(np.float32)

    def taper(first: int, last: int, fraction: float = 0.12) -> np.ndarray:
        count = max(1, last - first)
        distance = np.minimum(np.arange(count), np.arange(count)[::-1]).astype(np.float32)
        length = max(1.0, count * fraction)
        return np.clip(distance / length, 0.0, 1.0) ** 0.42

    def draw_ribbon(
        points: np.ndarray,
        normals: np.ndarray,
        offsets: np.ndarray,
        widths: np.ndarray,
        fill: tuple[int, int, int],
        first: int = 0,
        last: int | None = None,
        *,
        tapered: bool,
    ) -> None:
        end = sample_count if last is None else min(sample_count, last)
        start = max(0, first)
        if end - start < 3:
            return
        local_widths = widths[start:end].copy()
        if tapered:
            local_widths *= taper(start, end)
        local_centers = points[start:end] + normals[start:end] * offsets[start:end, None]
        half = normals[start:end] * (local_widths[:, None] * 0.5)
        polygon = np.concatenate((local_centers + half, (local_centers - half)[::-1]), axis=0)
        draw.polygon([tuple(point) for point in np.rint(polygon).astype(np.int32)], fill=fill)

    spiral_geometry: list[tuple[np.ndarray, np.ndarray]] = []
    edge_profiles: list[tuple[np.ndarray, np.ndarray]] = []

    # Each color arm is assembled from overlapping marker passes rather than a
    # single filled contour.  The pointed joins and independently wandering
    # passes give the piece its broad painted masses.
    body_widths = (0.55, 0.32, 0.60, 0.57, 0.64, 0.53, 0.59, 0.55)
    for color_index, color in enumerate(palette):
        phase = (color_index + 0.5) / band_count
        angular_wander = 0.009 * profile(24) + 0.003 * profile(95)
        theta = TAU * (phase - radial_phase + angular_wander)
        points = center + np.column_stack((np.cos(theta), np.sin(theta))) * (radius * scale)[:, None]
        tangent = np.gradient(points, axis=0)
        tangent /= np.maximum(np.linalg.norm(tangent, axis=1, keepdims=True), 1e-5)
        normals = np.column_stack((-tangent[:, 1], tangent[:, 0])).astype(np.float32)
        spiral_geometry.append((points.astype(np.float32), normals))

        body_width = body_widths[color_index]
        nominal_half = band_spacing * body_width * 0.5
        edge_profiles.append((nominal_half, nominal_half))
        middle_start = float(rng.uniform(0.32, 0.56))
        outer_start = float(rng.uniform(0.76, 0.98))
        segments = (
            (0.035, float(rng.uniform(0.58, 0.82))),
            (middle_start, float(rng.uniform(0.98, 1.22))),
            (outer_start, 1.52),
        )
        ink_factors = (0.86, 1.00, 0.93)
        for segment_index, (start_radius, end_radius) in enumerate(segments):
            first = int(np.searchsorted(radius, start_radius))
            last = int(np.searchsorted(radius, end_radius))
            progress = np.zeros(sample_count, dtype=np.float32)
            progress[first:last] = np.linspace(0.0, 1.0, last - first, dtype=np.float32)
            offsets = band_spacing * (
                float(rng.uniform(-0.09, 0.09))
                + float(rng.uniform(-0.18, 0.18)) * progress
                + 0.095 * profile(16)
                + 0.030 * profile(72)
            )
            widths = np.minimum(
                band_spacing * 0.96,
                band_spacing
                * body_width
                * float(rng.uniform(0.82, 1.10))
                * np.clip(1.0 + 0.34 * profile(13) + 0.19 * profile(64), 0.22, 1.58),
            )
            ink_factor = ink_factors[(color_index + segment_index) % len(ink_factors)]
            ink_color = tuple(round(channel * ink_factor) for channel in color)
            draw_ribbon(points, normals, offsets, widths, ink_color, first, last, tapered=True)

        for _ in range(3):
            start_radius = float(rng.uniform(0.045, 1.08))
            length = float(rng.uniform(0.24, 0.72))
            first = int(np.searchsorted(radius, start_radius))
            last = int(np.searchsorted(radius, min(1.52, start_radius + length)))
            offsets = band_spacing * (
                float(rng.uniform(-0.42, 0.42)) + 0.045 * profile(19) + 0.014 * profile(82)
            )
            widths = band_spacing * float(rng.uniform(0.035, 0.12)) * np.clip(
                1.0 + 0.42 * profile(25),
                0.24,
                1.85,
            )
            draw_ribbon(points, normals, offsets, widths, color, first, last, tapered=True)

    # Bristle hairs and partial marker passes make ragged silhouettes outside
    # the main bodies.  Each is a long tapered curve, never a repeated texture.
    for color_index, color in enumerate(palette):
        points, normals = spiral_geometry[color_index]
        left, right = edge_profiles[color_index]
        for side in (-1.0, 1.0):
            edge = right if side < 0 else left
            for _ in range(4):
                start_radius = float(rng.uniform(0.045, 1.05))
                length = float(rng.uniform(0.22, 0.82))
                first = int(np.searchsorted(radius, start_radius))
                last = int(np.searchsorted(radius, min(1.52, start_radius + length)))
                offset_fraction = float(rng.uniform(0.96, 1.38))
                offsets = side * (edge * offset_fraction + band_spacing * 0.025 * profile(17))
                widths = band_spacing * float(rng.uniform(0.014, 0.052)) * np.clip(
                    1.0 + 0.35 * profile(24),
                    0.25,
                    1.8,
                )
                draw_ribbon(points, normals, offsets, widths, color, first, last, tapered=True)

        for _ in range(18):
            index = int(rng.integers(5, sample_count - 5))
            normal = normals[index]
            tangent = np.asarray((normal[1], -normal[0]), dtype=np.float32)
            side = float(rng.choice((-1.0, 1.0)))
            slant = float(rng.uniform(-0.18, 0.18))
            direction = tangent * math.cos(slant) + normal * math.sin(slant)
            chip_center = points[index] + normal * band_spacing[index] * side * float(rng.uniform(0.32, 0.58))
            chip_length = scale * float(rng.uniform(0.012, 0.120))
            chip_width = max(1.0, band_spacing[index] * float(rng.uniform(0.012, 0.070)))
            polygon = (
                chip_center - direction * chip_length * 0.56,
                chip_center + normal * chip_width * 0.5,
                chip_center + direction * chip_length * 0.44,
                chip_center - normal * chip_width * 0.5,
            )
            draw.polygon([tuple(point) for point in np.rint(polygon).astype(np.int32)], fill=color)

    # Carve long parallel bristle channels and a few broad diagonal gouges.
    # Tapering every segment produces pointed joins and overlapping dry strokes
    # instead of dotted noise or evenly spaced rings.
    black = (0, 0, 0)
    for color_index in range(band_count):
        points, normals = spiral_geometry[color_index]
        for track in range(9):
            track_offset = float(rng.uniform(-0.38, 0.38))
            start_radius = float(rng.uniform(0.035, 1.18))
            length = float(rng.uniform(0.20, 0.78) if track > 1 else rng.uniform(0.72, 1.18))
            first = int(np.searchsorted(radius, start_radius))
            last = int(np.searchsorted(radius, min(1.52, start_radius + length)))
            track_progress = np.zeros(sample_count, dtype=np.float32)
            track_progress[first:last] = np.linspace(0.0, 1.0, last - first, dtype=np.float32)
            drift = float(rng.uniform(-0.22, 0.22))
            offsets = band_spacing * (
                track_offset
                + drift * track_progress
                + 0.140 * profile(12)
                + 0.048 * profile(55)
            )
            widths = np.maximum(
                1.0,
                band_spacing * float(rng.uniform(0.008, 0.024)) * np.clip(
                    1.0 + 0.48 * profile(31),
                    0.18,
                    2.1,
                ),
            )
            tapered = True
            draw_ribbon(points, normals, offsets, widths, black, first, last, tapered=tapered)

        for _ in range(6):
            start_radius = float(rng.uniform(0.06, 1.12))
            length = float(rng.uniform(0.25, 0.72))
            first = int(np.searchsorted(radius, start_radius))
            last = int(np.searchsorted(radius, min(1.52, start_radius + length)))
            if last - first < 3:
                continue
            progress = np.zeros(sample_count, dtype=np.float32)
            progress[first:last] = np.linspace(0.0, 1.0, last - first, dtype=np.float32)
            start_offset = float(rng.uniform(-0.42, 0.42))
            end_offset = float(np.clip(start_offset + rng.uniform(-1.0, 1.0), -0.68, 0.68))
            offsets = band_spacing * (
                start_offset + (end_offset - start_offset) * progress + 0.070 * profile(12)
            )
            widths = band_spacing * float(rng.uniform(0.035, 0.140)) * np.clip(
                1.0 + 0.42 * profile(18),
                0.28,
                1.90,
            )
            draw_ribbon(points, normals, offsets, widths, black, first, last, tapered=True)

        # Short pointed chips reproduce the little skips left by individual
        # bristles.  Their local direction follows the spiral tangent with a
        # small hand-drawn slant, so they read as scratches rather than dots.
        painted_chips = 0
        attempts = 0
        while painted_chips < 36 and attempts < 180:
            attempts += 1
            index = int(rng.integers(5, sample_count - 5))
            normal = normals[index]
            tangent = np.asarray((normal[1], -normal[0]), dtype=np.float32)
            slant = float(rng.uniform(-0.20, 0.20))
            direction = tangent * math.cos(slant) + normal * math.sin(slant)
            chip_center = points[index] + normal * band_spacing[index] * float(rng.uniform(-0.42, 0.42))
            pixel_x = int(np.clip(round(float(chip_center[0])), 0, width - 1))
            pixel_y = int(np.clip(round(float(chip_center[1])), 0, height - 1))
            if max(image.getpixel((pixel_x, pixel_y))) < 40:
                continue
            chip_length = scale * float(rng.uniform(0.012, 0.120))
            chip_width = max(1.0, band_spacing[index] * float(rng.uniform(0.015, 0.080)))
            polygon = (
                chip_center - direction * chip_length * 0.54,
                chip_center + normal * chip_width * 0.5,
                chip_center + direction * chip_length * 0.46,
                chip_center - normal * chip_width * 0.5,
            )
            draw.polygon([tuple(point) for point in np.rint(polygon).astype(np.int32)], fill=black)
            painted_chips += 1

    core_angles = np.linspace(0.0, TAU, 64, endpoint=False, dtype=np.float32)
    core_radius = scale * np.clip(
        0.090 + 0.018 * np.sin(core_angles * 5.0 + 0.8) + 0.010 * np.sin(core_angles * 11.0 - 0.4),
        0.058,
        0.120,
    )
    core = center + np.column_stack((np.cos(core_angles), np.sin(core_angles))) * core_radius[:, None]
    draw.polygon([tuple(point) for point in np.rint(core).astype(np.int32)], fill=black)
    return image


def _rainbow_swirl(width: int, height: int) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.49750, 0.50875))
    knots = np.asarray([0.015, 0.030, 0.050, 0.075, 0.100, 0.150, 0.220, 0.320, 0.450, 0.600, 0.800, 1.000, 1.200, 1.400], dtype=np.float32)
    radial_phase = np.interp(
        radius,
        knots,
        np.asarray([0.04347, 0.03745, 0.03184, 0.02455, 0.01898, 0.00672, -0.01244, -0.04393, -0.08287, -0.12798, -0.19020, -0.25111, -0.31974, -0.37391], dtype=np.float32),
    ).astype(np.float32)
    hue = -angle / TAU + radial_phase
    saturation = np.clip(0.86 - 0.05 * np.exp(-radius * 2.0), 0.74, 0.9)
    value = _rainbow_value(hue) * (0.98 - 0.06 * np.exp(-((radius - 0.24) / 0.18) ** 2))
    rgb = hsv_image(hue, saturation, value)
    return image_from_array(rgb)


def _curved_sectors(
    width: int,
    height: int,
    palette: list[str],
    *,
    turns: float,
    power: float,
    angle_sign: float,
    offset: float,
    center: tuple[float, float],
    design_id: str,
    shadow: bool,
) -> Image.Image:
    _, _, radius, angle = _preview_fitted_grid(width, height, design_id, center)
    count = len(palette)
    phase = np.mod((angle_sign * angle + turns * radius**power + offset) / TAU * count, count)
    labels = np.floor(phase).astype(np.int16)
    rgb = palette_image(labels, palette)
    if shadow:
        rgb = _boundary_shadow(rgb, phase - labels, 0.18)
    return image_from_array(rgb)


def _classic_beachball(width: int, height: int) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.4985, 0.5000))
    knots = np.asarray(
        [0.01, 0.03, 0.05, 0.08, 0.12, 0.18, 0.26, 0.36, 0.48, 0.62, 0.78, 0.96, 1.15, 1.35, 1.45],
        dtype=np.float32,
    )
    radial_phase = np.interp(
        radius,
        knots,
        np.asarray(
            [2.3019, 2.3494, 2.3439, 2.3691, 2.3859, 2.4127, 2.4527, 2.5087, 2.5676, 2.6377, 2.7175, 2.8085, 2.9017, 3.0000, 3.0500],
            dtype=np.float32,
        ),
    ).astype(np.float32)
    phase = np.mod((angle + radial_phase) / TAU * len(BEACHBALL), len(BEACHBALL))
    return image_from_array(palette_image(np.floor(phase).astype(np.int16), BEACHBALL))


def _spokes(width: int, height: int, *, center_radius: float) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.5, 0.5))
    palette = [RAINBOW_SPOKES[index] for index in (0, 2, 3, 4, 5, 6)]
    count = 30
    phase = np.mod(angle / TAU * count + 5.0, count)
    labels = np.floor(phase).astype(np.int16)
    rgb = palette_image(labels, palette)
    fraction = phase - labels
    separator = np.minimum(fraction, 1.0 - fraction) < (1.0 / 6.0)
    rgb[separator] = 0.0
    core = smoothstep(center_radius + 0.018, center_radius - 0.015, radius)
    rgb *= 1.0 - core[..., None]
    return image_from_array(rgb)


def _multi_spiral(width: int, height: int, palette: list[str]) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.5, 0.5))
    count = len(palette)
    knots = np.asarray(
        [0.01, 0.03, 0.05, 0.08, 0.12, 0.18, 0.26, 0.36, 0.48, 0.62, 0.78, 0.96, 1.15, 1.35, 1.45],
        dtype=np.float32,
    )
    radial_phase = np.interp(
        radius,
        knots,
        np.asarray(
            [0.9686, 0.7653, 0.5067, 0.1047, -0.5014, -1.4834, -2.9088, -4.6664, -6.4455, -7.7250, -8.9585, -10.2855, -11.5097, -12.7049, -13.3000],
            dtype=np.float32,
        ),
    ).astype(np.float32)
    phase = np.mod((angle + radial_phase) / TAU * count, count)
    labels = np.floor(phase).astype(np.int16)
    return image_from_array(palette_image(labels, palette))


def _sunburst(width: int, height: int, colors: tuple[str, str]) -> Image.Image:
    _, _, radius, angle = _preview_fitted_grid(width, height, "spinner_30c", (0.500, 0.500))
    light, dark = (parse_color(color) for color in colors)
    degrees = np.mod(np.degrees(angle), 360.0)
    intervals = (
        (13.0, 21.5), (31.2, 39.0), (53.6, 58.4), (71.4, 81.0),
        (104.4, 112.8), (121.4, 131.2), (144.4, 154.1), (163.3, 170.5),
        (185.2, 191.4), (204.4, 215.1), (228.9, 238.5), (261.9, 271.0),
        (279.1, 282.7), (288.6, 292.4), (300.0, 306.5), (323.5, 332.8),
        (340.9, 350.6),
    )
    ray = np.zeros((height, width), dtype=bool)
    edge_distance = np.full((height, width), 180.0, dtype=np.float32)
    for start, end in intervals:
        ray |= (degrees >= start) & (degrees <= end)
        for boundary in (start, end):
            delta = np.abs(degrees - boundary)
            edge_distance = np.minimum(edge_distance, np.minimum(delta, 360.0 - delta))
    ray &= radius > 0.40
    rgb = np.broadcast_to(light, (height, width, 3)).copy()
    rgb[ray] = dark
    bevel = np.exp(-edge_distance * 0.55, dtype=np.float32)
    bevel *= smoothstep(0.38, 0.45, radius)
    rgb *= 1.0 - 0.14 * bevel[..., None]
    return image_from_array(rgb)


def _teardrops(width: int, height: int, accent: str) -> Image.Image:
    _, _, radius, angle = _preview_fitted_grid(width, height, "spinner_31c", (0.502, 0.498))
    phase = np.mod(2.0 * angle - 11.945 * radius**0.837 + 5.797, TAU)
    grow = np.sqrt(np.minimum(radius, 1.0), dtype=np.float32)
    red_width = -0.250 + 0.900 * grow
    blue_width = -0.450 + 1.700 * grow
    d_accent = np.minimum(phase, TAU - phase)
    shifted = np.mod(phase - 3.327, TAU)
    d_blue = np.minimum(shifted, TAU - shifted)
    accent_mask = d_accent < red_width
    blue_mask = d_blue < blue_width
    rgb = np.zeros((height, width, 3), dtype=np.float32)
    accent_color = parse_color(accent)
    blue_color = parse_color(TEARDROP_BLUE)
    accent_shade = 0.90 + 0.10 * np.cos(np.clip(d_accent / np.maximum(red_width, 1e-4), 0, 1) * math.pi)
    blue_shade = 0.90 + 0.10 * np.cos(np.clip(d_blue / np.maximum(blue_width, 1e-4), 0, 1) * math.pi)
    rgb[blue_mask] = blue_color * blue_shade[blue_mask, None]
    rgb[accent_mask] = accent_color * accent_shade[accent_mask, None]
    return image_from_array(rgb)


def _whirlpool_duo(width: int, height: int, colors: tuple[str, str]) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.497, 0.494))
    phase = np.mod((angle + 6.048 * radius**0.379 + 2.131) * 4.0, TAU)
    first = parse_color(colors[0])
    second = parse_color(colors[1])
    boundary = 3.689
    rgb = np.where((phase < boundary)[..., None], first, second).astype(np.float32)
    distance = np.minimum(np.abs(phase - boundary), np.minimum(phase, TAU - phase))
    glow = np.exp(-distance * 32.0, dtype=np.float32)
    rgb = np.clip(rgb + glow[..., None] * 0.08, 0.0, 1.0)
    return image_from_array(rgb)


def _wobbly(width: int, height: int, colors: tuple[str, str]) -> Image.Image:
    _, _, radius, angle = _preview_fitted_grid(width, height, "spinner_36", (0.539, 0.519))
    wobble = (
        -0.050 * np.sin(angle)
        - 0.119 * np.cos(angle)
        - 0.034 * np.sin(2.0 * angle)
        + 0.005 * np.cos(2.0 * angle)
        + 0.008 * np.sin(3.0 * angle)
        + 0.037 * np.cos(3.0 * angle)
        - 0.066 * np.sin(4.0 * angle)
        + 0.081 * np.cos(4.0 * angle)
        - 0.015 * np.sin(5.0 * angle)
        - 0.008 * np.cos(5.0 * angle)
        + 0.030 * np.sin(6.0 * angle)
        - 0.009 * np.cos(6.0 * angle)
        + 0.015 * np.sin(7.0 * angle)
        - 0.018 * np.cos(7.0 * angle)
        + 0.018 * np.sin(8.0 * angle)
        - 0.021 * np.sin(9.0 * angle)
        + 0.022 * np.cos(9.0 * angle)
    )
    # Two full periods around the origin keep the polar seam continuous and
    # form the paired, hand-wobbled spiral.
    phase = np.mod(7.180 * radius**0.449 - 2.0 * angle / TAU + 0.247 + wobble, 1.0)
    first = parse_color(colors[0])
    second = parse_color(colors[1])
    rgb = np.where((phase < 0.634)[..., None], first, second).astype(np.float32)
    return image_from_array(rgb)


def _radial_stamp(width: int, height: int, colors: tuple[str, ...]) -> Image.Image:
    _, _, radius, _ = coordinate_grid(width, height, center=(0.5, 0.5))
    stops = np.asarray([0.0, 0.25, 0.5, 0.75, 1.0, 1.2, 1.4], dtype=np.float32)
    palette = np.stack([parse_color(color) for color in colors])
    rgb = np.empty((height, width, 3), dtype=np.float32)
    for channel in range(3):
        rgb[..., channel] = np.interp(radius, stops, palette[:, channel]).astype(np.float32)
    return image_from_array(rgb)


def _stamp_dots(width: int, height: int, layer_index: int, seed: int, key: str) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    del key
    rng = rng_for(seed, "stamp")
    cx, cy = width / 2, height / 2
    output_scale = min(width, height) / 2600.0
    for ring_index in range(49):
        source_radius = 122.0 + ring_index * 31.06
        radius = source_radius * output_scale
        points = max(16, round(TAU * source_radius / 25.0))
        offset = ring_index * 0.016 + (layer_index - 1) * TAU / 12.0
        offset += float(rng.uniform(-0.004, 0.004))
        outer_falloff = math.exp(-((max(source_radius - 650.0, 0.0) / 455.0) ** 2))
        inner_growth = 0.74 + 0.26 * float(np.clip((source_radius - 100.0) / 140.0, 0.0, 1.0))
        dot_radius = max(0.45, 10.0 * outer_falloff * inner_growth) * output_scale
        for point in range(points):
            theta = TAU * point / points + offset
            jitter = float(rng.uniform(-0.18, 0.18)) * max(dot_radius, 0.5)
            x = cx + math.cos(theta) * (radius + jitter)
            y = cy + math.sin(theta) * (radius + jitter)
            draw.ellipse((x - dot_radius, y - dot_radius, x + dot_radius, y + dot_radius), fill=(0, 0, 0, 255))
    return image


def _hud_arcs(width: int, height: int, color: str, layer_index: int, seed: int, key: str) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    del seed, key
    rgb = tuple(int(round(value * 255)) for value in parse_color(color))
    configs = {
        1: (
            (270, 49, ((139, 277), (346, 484))),
            (669, 65, ((50, 145), (210, 300), (320, 403))),
        ),
        2: (
            (497, 65, ((116, 277), (360, 457))),
            (841, 46, ((9, 41), (49, 75), (92, 211), (228, 275), (317, 365))),
        ),
        3: (
            (384, 23, ((5, 107), (135, 228), (250, 344))),
            (612, 15, ((16, 58), (95, 148), (164, 238), (265, 368))),
            (785, 26, ((56, 89), (146, 238), (260, 328), (336, 389))),
            (957, 14, ((16, 81), (106, 186), (226, 246), (257, 303), (316, 343), (346, 351))),
        ),
        4: (
            (327, 18, ((29, 185), (224, 365))),
            (441, 10, ((33, 142), (164, 195), (218, 344))),
            (555, 18, ((80, 174), (184, 228), (290, 317), (322, 428))),
            (728, 14, ((21, 57), (74, 165), (221, 291), (302, 344))),
            (899, 7, ((50, 67), (90, 119), (131, 161), (171, 237), (270, 327), (337, 389))),
            (1014, 25, ((61, 143), (161, 218), (224, 233), (322, 395))),
        ),
    }
    scale = min(width, height) / 2600.0
    cx, cy = width / 2, height / 2
    for source_radius, source_stroke, segments in configs[layer_index]:
        radius = source_radius * scale
        stroke = max(1, round(source_stroke * scale))
        outer_radius = radius + stroke / 2
        bounds = (cx - outer_radius, cy - outer_radius, cx + outer_radius, cy + outer_radius)
        for start, end in segments:
            draw.arc(bounds, start=start, end=end, fill=(*rgb, 255), width=stroke)
            cap_radius = stroke / 2
            for angle in (start, end):
                radians = math.radians(angle)
                x = cx + math.cos(radians) * radius
                y = cy + math.sin(radians) * radius
                draw.ellipse(
                    (x - cap_radius, y - cap_radius, x + cap_radius, y + cap_radius),
                    fill=(*rgb, 255),
                )
    return image


def _fiber_orbits(width: int, height: int, color: str, layer_index: int, seed: int, key: str) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    rng = rng_for(seed, "fiber", key, layer_index)
    rgb = tuple(int(round(value * 255)) for value in parse_color(color))
    scale = min(width, height) / 2600.0
    cx, cy = width / 2, height / 2
    radii_by_layer = {
        1: (28, 38, 58, 70, 78, 98, 173, 196, 205, 215, 232, 256, 269, 291, 299, 324, 376, 398, 420, 435, 486, 520, 544, 555, 613, 647, 667, 701, 770, 823, 845, 941, 956, 1020, 1145, 1162, 1202, 1237),
        2: (40, 51, 65, 91, 134, 191, 279, 293, 331, 375, 387, 441, 450, 522, 541, 556, 599, 608, 626, 636, 669, 692, 716, 734, 796, 869, 943, 992, 1123, 1190, 1210, 1228),
        3: (10, 22, 30, 55, 70, 87, 100, 114, 126, 154, 202, 224, 246, 263, 274, 293, 303, 335, 350, 379, 395, 419, 451, 472, 480, 515, 529, 557, 575, 586, 669, 722, 739, 781, 882, 959, 979, 1024, 1071, 1176, 1198, 1220, 1270),
        4: (17, 36, 60, 96, 110, 154, 199, 218, 238, 251, 271, 286, 322, 341, 387, 409, 433, 447, 500, 541, 552, 579, 644, 700, 766, 793, 825, 966, 1019, 1164, 1251),
    }
    for source_radius in radii_by_layer[layer_index]:
        radius = source_radius * scale
        eccentric = float(rng.uniform(-2.5, 2.5)) * scale
        bounds = (cx - radius + eccentric, cy - radius, cx + radius + eccentric, cy + radius)
        stroke = max(1, round(float(rng.uniform(3.5, 7.0)) * scale))
        draw.ellipse(bounds, outline=(*rgb, int(rng.integers(18, 56))), width=stroke)
        for _ in range(int(rng.integers(2, 7))):
            start = float(rng.uniform(-180, 360))
            length = float(rng.uniform(8, 82))
            alpha = int(rng.integers(75, 191))
            draw.arc(bounds, start=start, end=start + length, fill=(*rgb, alpha), width=stroke)
    return image


def _hoops_background(width: int, height: int, value: str | tuple[str, ...]) -> Image.Image:
    if isinstance(value, str):
        rgb = tuple(int(round(channel * 255)) for channel in parse_color(value))
        return Image.new("RGB", (width, height), rgb)
    colors = [parse_color(color) for color in value]
    y = np.linspace(0.0, 1.0, height, dtype=np.float32)[:, None]
    x = np.linspace(0.0, 1.0, width, dtype=np.float32)[None, :]
    if len(colors) == 2:
        amount = np.clip((x + y) * 0.5, 0.0, 1.0)
        rgb = blend(colors[0], colors[1], amount)
    else:
        top = blend(colors[0], colors[1], x)
        bottom = blend(colors[2], colors[3], x)
        rgb = blend(top, bottom, y)
    return image_from_array(rgb)


def _hoop_layer(width: int, height: int, color: str, layer_index: int) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    rgb = tuple(int(round(channel * 255)) for channel in parse_color(color))
    scale = min(width, height) / 2
    cx, cy = width / 2, height / 2
    stroke = max(1, round(scale * 0.0147))
    radii_by_layer = {
        1: (0.037, 0.095, 0.172, 0.220, 0.309, 0.395, 0.467, 0.551, 0.625, 0.707, 0.832, 0.989),
        2: (0.058, 0.157, 0.226, 0.295, 0.402, 0.529, 0.604, 0.720, 0.842, 0.950),
        3: (0.028, 0.128, 0.230, 0.320, 0.411, 0.481, 0.572, 0.654, 0.735, 0.857),
        4: (0.015, 0.102, 0.199, 0.257, 0.356, 0.461, 0.633, 0.735, 0.824, 0.948),
    }
    for normalized_radius in radii_by_layer[layer_index]:
        radius = scale * normalized_radius
        outer_radius = radius + stroke / 2
        bounds = (cx - outer_radius, cy - outer_radius, cx + outer_radius, cy + outer_radius)
        draw.ellipse(bounds, outline=(*rgb, 255), width=stroke)
    return image


class RenderEngine:
    """Stateful renderer with a small cache for shared effect-family bases."""

    def __init__(self, *, seed: int = 0) -> None:
        self.seed = seed
        self._base_cache: dict[tuple[str, int, int], Image.Image] = {}

    def _cached_base(
        self,
        design_id: str,
        width: int,
        height: int,
        factory: Callable[[int, int], Image.Image],
    ) -> Image.Image:
        key = (design_id, width, height)
        if key not in self._base_cache:
            self._base_cache[key] = factory(width, height)
        return self._base_cache[key].copy()

    def render_layer(self, remix: RemixSpec, layer: LayerSpec, size: int) -> Image.Image:
        width, height = layer_dimensions(layer, size)
        design = remix.design_id
        key = layer.source_key
        rng = rng_for(self.seed, remix.remix_id, layer.index, key)

        if design == "spinner_7":
            if effect_name(remix.remix_id) == "marker":
                return _dry_marker_whirlpool(width, height, rng)
            base = self._cached_base(design, width, height, _rainbow_whirlpool)
            return apply_effect(base, effect_name(remix.remix_id), rng, design_id=design)
        if design == "spinner_11":
            base = self._cached_base(design, width, height, _rainbow_swirl)
            return apply_effect(base, effect_name(remix.remix_id), rng, design_id=design)
        if design == "spinner_23":
            base = self._cached_base(design, width, height, _classic_beachball)
            return apply_effect(base, effect_name(remix.remix_id), rng, design_id=design)
        if design == "spinner_23f":
            colors = BRIGHT_BEACHBALL[variant_number(remix.remix_id)]
            palette = colors if len(colors) == 6 else [colors[(index + 1) % len(colors)] for index in range(6)]
            return _curved_sectors(
                width,
                height,
                palette,
                turns=-0.093,
                power=1.423,
                angle_sign=-1.0,
                offset=0.755,
                center=(0.510, 0.495),
                design_id=design,
                shadow=True,
            )
        if design == "spinner_25b":
            effect = effect_name(remix.remix_id)
            small = effect.endswith("small")
            base = _spokes(width, height, center_radius=0.049 if small else 0.150)
            normalized_effect = {"basesmall": "base", "sharpsmall": "sharp"}.get(effect, effect)
            return apply_effect(base, normalized_effect, rng, design_id=design)
        if design == "spinner_28c":
            base = self._cached_base(design, width, height, lambda w, h: _multi_spiral(w, h, STREAKED))
            return apply_effect(base, effect_name(remix.remix_id), rng, design_id=design)
        if design == "spinner_30c":
            return _sunburst(width, height, SUNBURST[variant_number(remix.remix_id)])
        if design == "spinner_31c":
            return _teardrops(width, height, TEARDROPS[variant_number(remix.remix_id)])
        if design == "spinner_32":
            return _whirlpool_duo(width, height, WHIRLPOOL_DUO[variant_number(remix.remix_id)])
        if design == "spinner_36":
            return _wobbly(width, height, WOBBLY[variant_number(remix.remix_id)])
        if design == "spinner_17":
            if layer.index == 0:
                return Image.new("RGB", (width, height), "black")
            return _hud_arcs(width, height, HUD[variant_number(remix.remix_id)], layer.index, self.seed, key)
        if design == "spinner_18":
            if layer.index == 0:
                return _radial_stamp(width, height, STAMPED[variant_number(remix.remix_id)])
            return _stamp_dots(width, height, layer.index, self.seed, key)
        if design == "spinner_37b":
            if layer.index == 0:
                return Image.new("RGB", (width, height), "black")
            palette = FIBER_ORBIT[variant_number(remix.remix_id)]
            color = palette[0 if layer.index < 3 else 1]
            return _fiber_orbits(width, height, color, layer.index, self.seed, key)
        if design == "spinner_39b":
            if layer.index == 0:
                return _hoops_background(width, height, HOOPS_BACKGROUNDS[variant_number(remix.remix_id)])
            return _hoop_layer(width, height, HOOP_LAYER_COLORS[layer.index - 1], layer.index)
        if design in SPIRAL_DESIGN_IDS:
            return render_spiral(
                design,
                variant_number(remix.remix_id),
                width,
                height,
                layer.index,
                rng,
            )
        if design in OUTLIER_DESIGN_IDS:
            return render_outlier(
                design,
                variant_number(remix.remix_id),
                width,
                height,
                layer.index,
                self.seed,
            )
        raise ValueError(f"no renderer registered for {design}")
