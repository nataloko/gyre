"""Layered procedural spinner families."""

from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from .core import blend, coordinate_grid, image_from_array, parse_color, smoothstep
from .planetarium import render_planetarium


TAU = math.tau

AFTERGLOW: dict[str, tuple[str, ...]] = {
    "0": ("#06101f", "#1f3c88", "#00b7a8", "#93f9b9", "#e0d7ff"),
    "1": ("#210016", "#7a102f", "#e84545", "#ff9d3d", "#fff3b0"),
    "2": ("#09001f", "#3d128f", "#9146ff", "#ff4fd8", "#46e0ff"),
    "3": ("#042f32", "#0b6e69", "#57cc99", "#c7f9cc", "#f1fffa"),
    "4": ("#100707", "#3d0c11", "#9e1b32", "#f45b69", "#ffcf70"),
    "5": ("#10121d", "#2a3152", "#8996cb", "#d6ddff", "#fff8ed"),
}

TAFFY: dict[str, tuple[str, ...]] = {
    "0": ("#ff6b8a", "#ffb35c", "#fff0b8", "#71d6c5", "#7ca7ff"),
    "1": ("#031b4e", "#0466c8", "#4cc9f0", "#b8f2ff", "#ff70a6"),
    "2": ("#173f35", "#65a30d", "#d9ed92", "#fff7d6", "#f59e0b"),
    "3": ("#09090b", "#242038", "#725ac1", "#e54f9b", "#ffd23f"),
    "4": ("#7c2d3f", "#ff8c69", "#ffc6a8", "#fff0df", "#84dcc6"),
    "5": ("#25105c", "#6d28d9", "#c026d3", "#ff4ecd", "#47e5bc"),
}

PRISMATA: dict[str, tuple[tuple[str, ...], str]] = {
    "0": (("#26304f", "#5b6cce", "#9fe7f5", "#f8c8dc", "#fff4d6"), "#effaff"),
    "1": (("#08090d", "#171923", "#332b24", "#6d542d", "#ad8540"), "#ffd978"),
    "2": (("#062c25", "#0b6b53", "#21a179", "#8fd694", "#d7f9b1"), "#f6ffc9"),
    "3": (("#071a2d", "#0d47a1", "#31a9d6", "#a8e6ff", "#e6f7ff"), "#ffffff"),
    "4": (("#310014", "#9d174d", "#ef4444", "#f97316", "#fde047"), "#fff3a3"),
    "5": (("#24134f", "#6d28d9", "#c026d3", "#fb7185", "#22d3ee"), "#f5d0fe"),
}

ORBIT_GARDEN: dict[str, tuple[str, ...]] = {
    "0": ("#050b1d", "#121d3c", "#ffb000", "#8cff98", "#28d7fe"),
    "1": ("#16081c", "#351534", "#ff4d8d", "#ffb86c", "#d7a8ff"),
    "2": ("#02181d", "#063b46", "#00d4b4", "#65e4ff", "#d8ff75"),
    "3": ("#f4ead7", "#d9cbb6", "#ba3f1d", "#243b53", "#e0a458"),
    "4": ("#050b08", "#10261b", "#b6ff00", "#00f5d4", "#f8ff86"),
    "5": ("#10091f", "#281448", "#9b5de5", "#f15bb5", "#fee440"),
}

SPIRAL_DESIGN_IDS = frozenset(
    {
        "spinner_afterglow",
        "spinner_orbitgarden",
        "spinner_planetarium",
        "spinner_prismata",
        "spinner_taffy",
    }
)


def _palette(value: tuple[str, ...]) -> np.ndarray:
    return np.stack([parse_color(color) for color in value], axis=0)


def _cyclic_gradient(phase: np.ndarray, colors: tuple[str, ...]) -> np.ndarray:
    palette = _palette(colors)
    scaled = np.mod(phase, 1.0) * len(palette)
    index = np.floor(scaled).astype(np.int16)
    fraction = scaled - index
    amount = smoothstep(0.08, 0.92, fraction)
    return blend(palette[index], palette[(index + 1) % len(palette)], amount)


def _afterglow(
    width: int,
    height: int,
    colors: tuple[str, ...],
    rng: np.random.Generator,
) -> Image.Image:
    x, y, radius, angle = coordinate_grid(width, height, center=(0.502, 0.496))
    first_phase, second_phase = rng.uniform(0.0, TAU, 2)
    spiral = (
        angle / TAU
        - 0.61 * np.power(radius, 0.68, dtype=np.float32)
        + 0.041 * np.sin(4.0 * angle - 7.0 * radius + first_phase)
        + 0.018 * np.sin(9.0 * angle + 5.0 * radius + second_phase)
    )
    rgb = _cyclic_gradient(spiral, colors)

    band_position = np.mod(spiral * len(colors), 1.0)
    boundary_distance = np.minimum(band_position, 1.0 - band_position)
    filament = np.exp(-np.square(boundary_distance / 0.075), dtype=np.float32)
    echo = np.exp(
        -np.square((np.abs(np.sin(TAU * (spiral * 2.0 + radius * 1.7))) - 0.82) / 0.12),
        dtype=np.float32,
    )
    caustic = 0.5 + 0.5 * np.sin(13.0 * x - 11.0 * y + 4.0 * radius + first_phase)
    luminosity = 0.70 + 0.22 * np.exp(-radius * 0.7) + 0.22 * filament + 0.07 * echo
    luminosity += 0.025 * caustic
    rgb *= luminosity[..., None]

    bloom_color = parse_color(colors[-1])
    rgb = blend(rgb, bloom_color, np.clip(filament * 0.22 + echo * 0.07, 0.0, 0.3))
    core = np.exp(-np.square(radius / 0.055), dtype=np.float32)
    rgb = blend(rgb, np.ones(3, dtype=np.float32), core * 0.72)
    vignette = 1.0 - 0.35 * smoothstep(0.72, 1.43, radius)
    return image_from_array(np.clip(rgb * vignette[..., None], 0.0, 1.0))


def _taffy(
    width: int,
    height: int,
    colors: tuple[str, ...],
    rng: np.random.Generator,
) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.494, 0.508))
    phase_offset = float(rng.uniform(-0.04, 0.04))
    fold = (
        0.070 * np.sin(2.0 * angle + 5.0 * radius)
        + 0.035 * np.sin(5.0 * angle - 8.0 * radius)
        + 0.013 * np.sin(11.0 * angle + 3.0 * radius)
    )
    spiral = angle / TAU - 0.53 * np.power(radius, 0.73, dtype=np.float32) + fold
    scaled = np.mod(spiral + phase_offset, 1.0) * len(colors)
    index = np.floor(scaled).astype(np.int16)
    fraction = scaled - index
    palette = _palette(colors)
    transition = smoothstep(0.79, 0.99, fraction)
    rgb = blend(palette[index], palette[(index + 1) % len(palette)], transition)

    roll = 0.80 + 0.17 * (0.5 + 0.5 * np.cos(TAU * (fraction - 0.18)))
    crease = np.exp(-np.square((fraction - 0.80) / 0.055), dtype=np.float32)
    shine = np.exp(-np.square((fraction - 0.63) / 0.085), dtype=np.float32)
    rgb *= (roll - 0.20 * crease)[..., None]
    rgb = blend(rgb, np.ones(3, dtype=np.float32), shine * 0.18)

    center_gloss = np.exp(-np.square(radius / 0.12), dtype=np.float32)
    rgb = blend(rgb, parse_color(colors[2]), center_gloss * 0.34)
    vignette = 1.0 - 0.22 * smoothstep(0.85, 1.42, radius)
    return image_from_array(np.clip(rgb * vignette[..., None], 0.0, 1.0))


def _prismata(
    width: int,
    height: int,
    colors: tuple[str, ...],
    seam_color: str,
    rng: np.random.Generator,
) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height, center=(0.5, 0.5))
    twist = float(rng.uniform(4.8, 6.1))
    angular = angle / TAU * 18.0
    u = (angular + twist * radius + 0.38 * np.sin(3.0 * angle)).astype(np.float32)
    v = (radius * 11.5 + 0.31 * np.sin(5.0 * angle - 2.0 * radius)).astype(np.float32)
    cell_u = np.floor(u).astype(np.int32)
    cell_v = np.floor(v).astype(np.int32)
    fraction_u = u - cell_u
    fraction_v = v - cell_v
    triangle = (fraction_u + fraction_v > 1.0).astype(np.int32)

    wrapped_cell_u = np.mod(cell_u, 18)
    hashed = (
        wrapped_cell_u * 37
        + cell_v * 73
        + triangle * 17
        + wrapped_cell_u * cell_v * 3
    )
    palette = _palette(colors)
    labels = np.mod(hashed, len(palette))
    rgb = palette[labels]

    facet_light = 0.72 + 0.24 * (
        np.mod(hashed * 173, 1000).astype(np.float32) / 1000.0
    )
    directional = 0.92 + 0.08 * np.cos(angle - 0.8)
    rgb *= (facet_light * directional)[..., None]

    diagonal_distance = np.abs(fraction_u + fraction_v - 1.0) / math.sqrt(2.0)
    edge_distance = np.minimum.reduce(
        (
            fraction_u,
            1.0 - fraction_u,
            fraction_v,
            1.0 - fraction_v,
            diagonal_distance,
        )
    )
    seam = 1.0 - smoothstep(0.013, 0.050, edge_distance)
    seam *= 0.72 + 0.28 * np.exp(-radius * 0.45)
    rgb = blend(rgb, parse_color(seam_color), seam * 0.82)

    glint = np.exp(-np.square((edge_distance - 0.052) / 0.018), dtype=np.float32)
    glint *= np.clip(np.cos(angle + radius * 4.0), 0.0, 1.0)
    rgb = blend(rgb, np.ones(3, dtype=np.float32), glint * 0.17)
    vignette = 1.0 - 0.28 * smoothstep(0.8, 1.43, radius)
    return image_from_array(np.clip(rgb * vignette[..., None], 0.0, 1.0))


def _rgba(color: str, alpha: int) -> tuple[int, int, int, int]:
    channels = np.clip(parse_color(color) * 255.0 + 0.5, 0, 255).astype(np.uint8)
    return int(channels[0]), int(channels[1]), int(channels[2]), alpha


def _orbit_background(
    width: int,
    height: int,
    colors: tuple[str, ...],
    rng: np.random.Generator,
) -> Image.Image:
    _, _, radius, angle = coordinate_grid(width, height)
    center = parse_color(colors[0])
    edge = parse_color(colors[1])
    amount = smoothstep(0.03, 1.42, radius)
    rgb = blend(center, edge, amount)

    phase = float(rng.uniform(0.0, TAU))
    nebula = (0.5 + 0.5 * np.sin(3.0 * angle - 5.5 * radius + phase)) ** 3
    nebula *= np.exp(-np.square((radius - 0.58) / 0.5), dtype=np.float32)
    rgb = blend(rgb, parse_color(colors[4]), nebula * 0.075)
    image = image_from_array(np.clip(rgb, 0.0, 1.0))

    draw = ImageDraw.Draw(image)
    scale = min(width, height) / 2600.0
    star_count = max(24, round(width * height / 9200.0))
    for _ in range(star_count):
        x = float(rng.uniform(0, width))
        y = float(rng.uniform(0, height))
        radius_px = max(0.35, float(rng.choice((1.2, 1.6, 2.2, 3.4))) * scale)
        alpha = float(rng.uniform(0.45, 0.95))
        color = tuple(
            round(channel * alpha + 255 * (1.0 - alpha))
            for channel in _rgba(colors[4], 255)[:3]
        )
        draw.ellipse(
            (x - radius_px, y - radius_px, x + radius_px, y + radius_px),
            fill=color,
        )
    return image


def _finish_glow(glow: Image.Image, crisp: Image.Image, blur: float) -> Image.Image:
    softened = glow.filter(ImageFilter.GaussianBlur(max(0.6, blur)))
    return Image.alpha_composite(softened, crisp)


def _orbit_arcs(
    width: int,
    height: int,
    colors: tuple[str, ...],
    rng: np.random.Generator,
) -> Image.Image:
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    crisp = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    crisp_draw = ImageDraw.Draw(crisp)
    shorter = min(width, height)
    cx, cy = width / 2.0, height / 2.0
    for ring in range(14):
        radius = shorter * (0.055 + ring * 0.043)
        eccentricity = 1.0 + 0.09 * math.sin(ring * 1.7)
        bounds = (
            cx - radius * eccentricity,
            cy - radius,
            cx + radius * eccentricity,
            cy + radius,
        )
        stroke = max(1, round(shorter * (0.0013 + 0.00022 * (ring % 3))))
        color = colors[2 + ring % 3]
        offset = float(rng.uniform(0.0, 360.0))
        segment_count = 2 + ring % 4
        for segment in range(segment_count):
            start = offset + segment * 360.0 / segment_count
            length = 28.0 + float(rng.uniform(14.0, 82.0))
            glow_draw.arc(
                bounds,
                start=start,
                end=start + length,
                fill=_rgba(color, 95),
                width=stroke * 5,
            )
            crisp_draw.arc(
                bounds,
                start=start,
                end=start + length,
                fill=_rgba(color, 220),
                width=stroke,
            )
    return _finish_glow(glow, crisp, shorter * 0.006)


def _orbit_beads(
    width: int,
    height: int,
    colors: tuple[str, ...],
    rng: np.random.Generator,
) -> Image.Image:
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    crisp = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    crisp_draw = ImageDraw.Draw(crisp)
    shorter = min(width, height)
    cx, cy = width / 2.0, height / 2.0
    for ring in range(9):
        radius = shorter * (0.09 + ring * 0.062)
        point_count = 7 + ring * 2
        offset = float(rng.uniform(0.0, TAU))
        flatten = 0.83 + 0.10 * math.sin(ring * 1.3)
        for point in range(point_count):
            theta = TAU * point / point_count + offset
            x = cx + math.cos(theta) * radius
            y = cy + math.sin(theta) * radius * flatten
            length = shorter * (0.0065 + 0.0015 * (ring % 3))
            direction = np.asarray((math.cos(theta), math.sin(theta)), dtype=np.float32)
            normal = np.asarray((-direction[1], direction[0]), dtype=np.float32)
            center = np.asarray((x, y), dtype=np.float32)
            points = (
                center + direction * length * 1.8,
                center + normal * length,
                center - direction * length * 1.8,
                center - normal * length,
            )
            color = colors[2 + (ring + point) % 3]
            polygon = [tuple(item) for item in points]
            glow_draw.polygon(polygon, fill=_rgba(color, 100))
            crisp_draw.polygon(polygon, fill=_rgba(color, 225))
    return _finish_glow(glow, crisp, shorter * 0.005)


def _orbit_threads(
    width: int,
    height: int,
    colors: tuple[str, ...],
    rng: np.random.Generator,
) -> Image.Image:
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    crisp = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    crisp_draw = ImageDraw.Draw(crisp)
    shorter = min(width, height)
    cx, cy = width / 2.0, height / 2.0
    sample_count = max(180, round(shorter / 5.0))
    t = np.linspace(0.0, TAU * 3.2, sample_count, dtype=np.float32)
    for arm in range(6):
        offset = TAU * arm / 6.0 + float(rng.uniform(-0.035, 0.035))
        radius = shorter * (0.018 + 0.0185 * t)
        theta = t + offset + 0.13 * np.sin(5.0 * t + arm)
        x = cx + np.cos(theta) * radius
        y = cy + np.sin(theta) * radius
        points = [tuple(point) for point in np.column_stack((x, y))]
        color = colors[2 + arm % 3]
        stroke = max(1, round(shorter * (0.0010 + 0.00025 * (arm % 2))))
        glow_draw.line(points, fill=_rgba(color, 90), width=stroke * 5, joint="curve")
        crisp_draw.line(points, fill=_rgba(color, 205), width=stroke, joint="curve")
    return _finish_glow(glow, crisp, shorter * 0.0045)


def render_spiral(
    design_id: str,
    variant: str,
    width: int,
    height: int,
    layer_index: int,
    rng: np.random.Generator,
) -> Image.Image:
    """Render one layer from the layered spinner collection."""

    if design_id == "spinner_afterglow":
        return _afterglow(width, height, AFTERGLOW[variant], rng)
    if design_id == "spinner_taffy":
        return _taffy(width, height, TAFFY[variant], rng)
    if design_id == "spinner_prismata":
        colors, seam_color = PRISMATA[variant]
        return _prismata(width, height, colors, seam_color, rng)
    if design_id == "spinner_orbitgarden":
        colors = ORBIT_GARDEN[variant]
        renderers = (_orbit_background, _orbit_arcs, _orbit_beads, _orbit_threads)
        return renderers[layer_index](width, height, colors, rng)
    if design_id == "spinner_planetarium":
        return render_planetarium(variant, width, height, layer_index, rng)
    raise ValueError(f"no spiral renderer registered for {design_id}")
