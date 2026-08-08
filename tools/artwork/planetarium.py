"""Layered, planet-inspired spinner artwork."""

from __future__ import annotations

from dataclasses import dataclass
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from .core import blend, coordinate_grid, image_from_array, parse_color, smoothstep


TAU = math.tau


@dataclass(frozen=True, slots=True)
class PlanetariumPalette:
    space: tuple[str, str]
    nebula: str
    star: tuple[str, str]
    orbit: str
    worlds: tuple[tuple[str, str], ...]
    dust: str
    phase: float
    binary: bool = False


PLANETARIUM: dict[str, PlanetariumPalette] = {
    "0": PlanetariumPalette(
        space=("#020617", "#12234a"),
        nebula="#274690",
        star=("#fff7b2", "#ff9f1c"),
        orbit="#8ecae6",
        worlds=(
            ("#665c54", "#d8c3a5"),
            ("#0b4f8a", "#5ed6c8"),
            ("#7f2b1d", "#e07a5f"),
            ("#59463b", "#d9ad7c"),
            ("#8b754d", "#ead7a4"),
            ("#273caa", "#69a7ff"),
        ),
        dust="#f4d35e",
        phase=0.15,
    ),
    "1": PlanetariumPalette(
        space=("#130509", "#3d1018"),
        nebula="#7f1d1d",
        star=("#ffe0bd", "#ff3b30"),
        orbit="#d97757",
        worlds=(
            ("#3c1618", "#a44a3f"),
            ("#682c20", "#e76f51"),
            ("#46211a", "#bc6c25"),
            ("#351522", "#9d4edd"),
            ("#74321f", "#e9a66b"),
            ("#40131c", "#c44569"),
        ),
        dust="#ffb36b",
        phase=0.72,
    ),
    "2": PlanetariumPalette(
        space=("#080b2e", "#26134d"),
        nebula="#5b21b6",
        star=("#fff3bd", "#63d8ff"),
        orbit="#c4b5fd",
        worlds=(
            ("#18345e", "#68d8d6"),
            ("#4b247d", "#d98cff"),
            ("#283593", "#70a1ff"),
            ("#6f245f", "#ff79c6"),
            ("#3949ab", "#b9c6ff"),
            ("#145f65", "#72efdd"),
        ),
        dust="#f8b4ff",
        phase=1.31,
        binary=True,
    ),
    "3": PlanetariumPalette(
        space=("#03131f", "#123b58"),
        nebula="#0e7490",
        star=("#ffffff", "#8be8ff"),
        orbit="#b9efff",
        worlds=(
            ("#334155", "#cbd5e1"),
            ("#0e7490", "#a5f3fc"),
            ("#315175", "#93c5fd"),
            ("#4c6682", "#dbeafe"),
            ("#49718b", "#c5f2ff"),
            ("#1d4ed8", "#bfdbfe"),
        ),
        dust="#e0f7ff",
        phase=2.02,
    ),
    "4": PlanetariumPalette(
        space=("#200b28", "#4a1942"),
        nebula="#9d4edd",
        star=("#fff0d6", "#ff70a6"),
        orbit="#f0abfc",
        worlds=(
            ("#8f476a", "#ffafcc"),
            ("#5d5f9f", "#a2d2ff"),
            ("#805b87", "#cdb4db"),
            ("#8c5d3d", "#ffc8a2"),
            ("#546a7b", "#bde0fe"),
            ("#6b4978", "#e7c6ff"),
        ),
        dust="#ffe5ec",
        phase=2.68,
    ),
    "5": PlanetariumPalette(
        space=("#030806", "#111827"),
        nebula="#3b0764",
        star=("#efff8a", "#00f5d4"),
        orbit="#b6ff00",
        worlds=(
            ("#173f35", "#8cff98"),
            ("#30114f", "#d946ef"),
            ("#064e5f", "#22d3ee"),
            ("#4c1d95", "#a78bfa"),
            ("#365314", "#bef264"),
            ("#701a75", "#f0abfc"),
        ),
        dust="#f5ff90",
        phase=3.47,
    ),
}

@dataclass(frozen=True, slots=True)
class OrbitSpec:
    radius: float
    flattening: float
    tilt: float
    diameter: float
    phase: float


ORBIT_SPECS = (
    OrbitSpec(0.100, 0.78, -0.22, 0.045, 0.14),
    OrbitSpec(0.155, 0.88, 0.31, 0.056, 2.16),
    OrbitSpec(0.220, 0.70, -0.43, 0.070, 4.20),
    OrbitSpec(0.285, 0.82, 0.18, 0.090, 1.04),
    OrbitSpec(0.350, 0.66, -0.27, 0.124, 3.28),
    OrbitSpec(0.420, 0.76, 0.39, 0.098, 5.44),
)


def _rgba(color: str, alpha: int) -> tuple[int, int, int, int]:
    channels = np.clip(parse_color(color) * 255.0 + 0.5, 0, 255).astype(np.uint8)
    return int(channels[0]), int(channels[1]), int(channels[2]), alpha


def _planetarium_background(
    width: int,
    height: int,
    palette: PlanetariumPalette,
    rng: np.random.Generator,
) -> Image.Image:
    x, y, radius, angle = coordinate_grid(width, height)
    center = parse_color(palette.space[0])
    edge = parse_color(palette.space[1])
    radial_amount = 0.20 + 0.80 * smoothstep(0.0, 1.42, radius)
    rgb = blend(center, edge, radial_amount)

    tilt = 0.48 + 0.10 * math.sin(palette.phase)
    disk_x = x * math.cos(tilt) + y * math.sin(tilt)
    disk_y = -x * math.sin(tilt) + y * math.cos(tilt)
    disk = np.exp(
        -np.square(disk_y / (0.13 + 0.08 * radius)),
        dtype=np.float32,
    )
    disk *= np.exp(-radius * 0.62, dtype=np.float32)
    cloud_field = (
        0.48
        + 0.20 * np.sin(3.1 * disk_x + 5.7 * disk_y + palette.phase)
        + 0.17 * np.sin(7.3 * disk_x - 2.9 * disk_y - palette.phase * 0.7)
        + 0.10 * np.sin(13.0 * disk_x + 11.0 * disk_y + 1.4)
    )
    spiral = 0.5 + 0.5 * np.sin(2.0 * angle - 4.6 * radius + palette.phase)
    nebula_amount = disk * np.clip(cloud_field, 0.0, 1.0) * (0.045 + 0.10 * spiral)
    rgb = blend(rgb, parse_color(palette.nebula), nebula_amount)
    dust_lane = np.exp(-np.square(disk_y / 0.035), dtype=np.float32)
    dust_lane *= 0.10 * (0.35 + 0.65 * np.clip(cloud_field, 0.0, 1.0))
    rgb *= (1.0 - dust_lane)[..., None]
    vignette = 1.0 - 0.34 * smoothstep(0.82, 1.43, radius)
    image = image_from_array(np.clip(rgb * vignette[..., None], 0.0, 1.0))

    draw = ImageDraw.Draw(image)
    scale = min(width, height) / 2600.0
    star_count = max(72, round(width * height / 11800.0))
    star_colors = (palette.orbit, palette.star[0], palette.dust)
    for star_index in range(star_count):
        sx = float(rng.uniform(0, width))
        sy = float(rng.uniform(0, height))
        source_radius = float(rng.choice((0.8, 1.0, 1.2, 1.6, 2.4, 3.8)))
        point_radius = max(0.28, source_radius * scale)
        color = star_colors[int(rng.integers(0, len(star_colors)))]
        opacity = float(rng.uniform(0.28, 0.82))
        rgb_color = _rgba(color, 255)[:3]
        fill = tuple(round(channel * opacity) for channel in rgb_color)
        draw.ellipse(
            (
                sx - point_radius,
                sy - point_radius,
                sx + point_radius,
                sy + point_radius,
            ),
            fill=fill,
        )
        if star_index % 47 == 0 and point_radius >= 0.7:
            spike = point_radius * 3.4
            draw.line((sx - spike, sy, sx + spike, sy), fill=fill, width=1)
            draw.line((sx, sy - spike, sx, sy + spike), fill=fill, width=1)
    return image


def _working_dimensions(width: int, height: int) -> tuple[int, int, int]:
    factor = 2 if min(width, height) < 900 else 1
    return width * factor, height * factor, factor


def _finish_layer(image: Image.Image, width: int, height: int, factor: int) -> Image.Image:
    if factor == 1:
        return image
    return image.resize((width, height), Image.Resampling.LANCZOS)


def _stellar_surface(
    diameter: int,
    palette: PlanetariumPalette,
    phase: float,
    *,
    alternate: bool,
) -> Image.Image:
    axis = (
        np.arange(diameter, dtype=np.float32) + np.float32(0.5) - diameter / 2.0
    ) / (diameter / 2.0)
    x, y = np.meshgrid(axis, axis)
    radius = np.hypot(x, y, dtype=np.float32)
    angle = np.arctan2(y, x, dtype=np.float32)
    boundary = (
        1.0
        + 0.026 * np.sin(7.0 * angle + phase)
        + 0.014 * np.sin(13.0 * angle - phase * 1.7)
    )
    alpha = smoothstep(boundary + 0.028, boundary - 0.018, radius)

    core_color = parse_color(palette.star[0 if not alternate else 1])
    edge_color = parse_color(palette.star[1 if not alternate else 0])
    edge_amount = 0.18 + 0.72 * smoothstep(0.12, 1.0, radius)
    rgb = blend(core_color, edge_color, edge_amount)

    warp_x = x + 0.12 * np.sin(4.7 * y + phase) + 0.045 * np.sin(9.2 * x - 3.1 * y)
    warp_y = y + 0.11 * np.sin(4.1 * x - phase) + 0.040 * np.sin(7.7 * y + 2.8 * x)
    cells = (
        0.50
        + 0.16 * np.sin(8.3 * warp_x + 5.7 * warp_y + phase)
        + 0.12 * np.sin(-5.1 * warp_x + 9.6 * warp_y - phase * 0.8)
        + 0.07 * np.sin(12.7 * warp_x - 7.2 * warp_y + 1.4)
    )
    convection = 0.5 + 0.5 * np.sin(6.0 * angle - 10.0 * radius + phase)
    limb = 0.68 + 0.32 * np.sqrt(np.clip(1.0 - radius * radius, 0.0, 1.0))
    brightness = limb * (0.84 + 0.18 * np.clip(cells, 0.0, 1.0))
    fine_grain = (
        np.sin(21.3 * warp_x + 13.7 * warp_y)
        + np.sin(-17.9 * warp_x + 23.1 * warp_y + 1.1)
        + np.sin(27.4 * warp_x - 5.6 * warp_y - 0.7)
    ) / 3.0
    brightness *= 1.0 + 0.035 * fine_grain
    rgb *= brightness[..., None]
    hot_cells = smoothstep(0.64, 0.90, cells) * (0.35 + 0.65 * convection)
    rgb = blend(rgb, np.ones(3, dtype=np.float32), hot_cells * 0.10)
    spot_angle = phase * 0.63
    spot_x = 0.34 * math.cos(spot_angle)
    spot_y = 0.24 * math.sin(spot_angle)
    sunspots = np.exp(-((x - spot_x) ** 2 + (y - spot_y) ** 2) / 0.020)
    sunspots += 0.65 * np.exp(-((x + spot_x * 0.72) ** 2 + (y + 0.31) ** 2) / 0.011)
    rgb *= (1.0 - 0.075 * sunspots)[..., None]

    rgba = np.concatenate((np.clip(rgb, 0.0, 1.0), alpha[..., None]), axis=2)
    return image_from_array(rgba, mode="RGBA")


def _draw_star(
    glow: Image.Image,
    crisp: Image.Image,
    center: tuple[float, float],
    radius: float,
    palette: PlanetariumPalette,
    rng: np.random.Generator,
    *,
    alternate: bool,
) -> None:
    glow_draw = ImageDraw.Draw(glow)
    crisp_draw = ImageDraw.Draw(crisp)
    cx, cy = center
    halo_color = palette.star[1 if not alternate else 0]
    core_color = palette.star[0 if not alternate else 1]

    for ray in range(52):
        theta = TAU * ray / 52.0 + float(rng.uniform(-0.022, 0.022))
        inner = radius * float(rng.uniform(0.90, 1.04))
        outer = radius * float(rng.uniform(1.12, 2.55))
        start = (cx + math.cos(theta) * inner, cy + math.sin(theta) * inner)
        end = (cx + math.cos(theta) * outer, cy + math.sin(theta) * outer)
        glow_draw.line(
            (start, end),
            fill=_rgba(halo_color, int(rng.integers(22, 76))),
            width=max(1, round(radius * float(rng.uniform(0.025, 0.085)))),
        )

    glow_draw.ellipse(
        (cx - radius * 3.0, cy - radius * 3.0, cx + radius * 3.0, cy + radius * 3.0),
        fill=_rgba(halo_color, 42),
    )
    glow_draw.ellipse(
        (cx - radius * 1.75, cy - radius * 1.75, cx + radius * 1.75, cy + radius * 1.75),
        fill=_rgba(core_color, 86),
    )
    for loop in range(8):
        loop_radius = radius * float(rng.uniform(1.02, 1.34))
        bounds = (
            cx - loop_radius,
            cy - loop_radius,
            cx + loop_radius,
            cy + loop_radius,
        )
        start = float(rng.uniform(0.0, 360.0))
        crisp_draw.arc(
            bounds,
            start=start,
            end=start + float(rng.uniform(12.0, 42.0)),
            fill=_rgba(halo_color, int(rng.integers(80, 165))),
            width=max(1, round(radius * 0.035)),
        )

    diameter = max(12, round(radius * 2.0))
    surface = _stellar_surface(
        diameter,
        palette,
        float(rng.uniform(0.0, TAU)),
        alternate=alternate,
    )
    crisp.alpha_composite(
        surface,
        dest=(round(cx - diameter / 2.0), round(cy - diameter / 2.0)),
    )


def _stellar_frame(
    width: int,
    height: int,
    palette: PlanetariumPalette,
    rng: np.random.Generator,
) -> Image.Image:
    work_width, work_height, factor = _working_dimensions(width, height)
    glow = Image.new("RGBA", (work_width, work_height), (0, 0, 0, 0))
    crisp = Image.new("RGBA", (work_width, work_height), (0, 0, 0, 0))
    shorter = min(work_width, work_height)
    cx, cy = work_width / 2.0, work_height / 2.0

    if palette.binary:
        offset = shorter * 0.034
        theta = palette.phase * 0.55
        direction = (math.cos(theta) * offset, math.sin(theta) * offset)
        star_radius = shorter * 0.043
        centers = (
            (cx - direction[0], cy - direction[1]),
            (cx + direction[0], cy + direction[1]),
        )
        _draw_star(glow, crisp, centers[0], star_radius, palette, rng, alternate=False)
        _draw_star(glow, crisp, centers[1], star_radius, palette, rng, alternate=True)
    else:
        _draw_star(
            glow,
            crisp,
            (cx, cy),
            shorter * 0.061,
            palette,
            rng,
            alternate=False,
        )

    softened = glow.filter(ImageFilter.GaussianBlur(max(0.8, shorter * 0.020)))
    combined = Image.alpha_composite(softened, crisp)
    return _finish_layer(combined, width, height, factor)


def _planet_sprite(
    diameter: int,
    colors: tuple[str, str],
    light_angle: float,
    texture_phase: float,
    style: int,
) -> Image.Image:
    axis = (
        np.arange(diameter, dtype=np.float32) + np.float32(0.5) - diameter / 2.0
    ) / (diameter / 2.0)
    x, y = np.meshgrid(axis, axis)
    radius = np.hypot(x, y, dtype=np.float32)
    z = np.sqrt(np.clip(1.0 - radius * radius, 0.0, 1.0), dtype=np.float32)
    longitude = np.arctan2(x, np.maximum(z, 1e-4), dtype=np.float32)
    latitude = np.arcsin(np.clip(y, -1.0, 1.0), dtype=np.float32)

    light = np.asarray(
        (0.88 * math.cos(light_angle), 0.88 * math.sin(light_angle), 0.34),
        dtype=np.float32,
    )
    light /= np.linalg.norm(light)
    light_dot = x * light[0] + y * light[1] + z * light[2]
    diffuse = smoothstep(-0.28, 0.72, light_dot)
    shade = 0.055 + 0.945 * diffuse

    if style >= 3:
        bands = 0.5 + 0.5 * np.sin(
            latitude * (18.0 + style * 1.7)
            + 1.3 * np.sin(longitude * 2.0 + texture_phase)
            + texture_phase
        )
        fine_bands = 0.5 + 0.5 * np.sin(latitude * 43.0 - longitude * 1.6)
        storm = np.exp(
            -(
                np.square((longitude - 0.26 * math.sin(texture_phase)) / 0.34)
                + np.square((latitude + 0.17) / 0.13)
            ),
            dtype=np.float32,
        )
        texture = np.clip(0.12 + 0.58 * bands + 0.14 * fine_bands + 0.20 * storm, 0.0, 1.0)
    else:
        terrain = (
            0.62 * np.sin(longitude * 4.2 + latitude * 5.4 + texture_phase)
            + 0.31 * np.sin(longitude * 8.7 - latitude * 3.8 - texture_phase * 0.8)
            + 0.18 * np.sin(longitude * 15.0 + latitude * 12.0 + 1.7)
        )
        texture = smoothstep(-0.20, 0.32, terrain)
        if style in {0, 2}:
            crater = (
                np.exp(-((longitude + 0.48) ** 2 + (latitude - 0.18) ** 2) / 0.022)
                + 0.7 * np.exp(-((longitude - 0.31) ** 2 + (latitude + 0.27) ** 2) / 0.013)
            )
            texture = np.clip(texture - crater * 0.28, 0.0, 1.0)

    rgb = blend(parse_color(colors[0]), parse_color(colors[1]), texture)
    rgb *= shade[..., None]

    if style == 1:
        clouds = smoothstep(
            0.72,
            0.96,
            0.5 + 0.5 * np.sin(longitude * 9.0 + latitude * 13.0 + texture_phase),
        )
        rgb = blend(rgb, np.ones(3, dtype=np.float32), clouds * diffuse * 0.22)

    specular = np.exp(
        -(
            np.square(x - light[0] * 0.48)
            + np.square(y - light[1] * 0.48)
        )
        / (0.026 if style == 1 else 0.016),
        dtype=np.float32,
    )
    rgb = blend(rgb, np.ones(3, dtype=np.float32), specular * (0.34 if style == 1 else 0.16))
    rim = smoothstep(0.80, 1.0, radius) * smoothstep(-0.12, 0.48, light_dot)
    rgb = blend(rgb, parse_color(colors[1]), rim * 0.36)

    alpha = smoothstep(1.035, 0.965, radius)
    rgba = np.concatenate((np.clip(rgb, 0.0, 1.0), alpha[..., None]), axis=2)
    return image_from_array(rgba, mode="RGBA")


def _orbit_points(
    center: tuple[float, float],
    shorter: float,
    spec: OrbitSpec,
    start: float,
    end: float,
    samples: int,
) -> list[tuple[float, float]]:
    theta = np.linspace(start, end, samples, dtype=np.float32)
    radius = shorter * spec.radius
    local_x = np.cos(theta) * radius
    local_y = np.sin(theta) * radius * spec.flattening
    cosine = math.cos(spec.tilt)
    sine = math.sin(spec.tilt)
    x = center[0] + local_x * cosine - local_y * sine
    y = center[1] + local_x * sine + local_y * cosine
    return [tuple(point) for point in np.column_stack((x, y))]


def _orbit_position(
    center: tuple[float, float],
    shorter: float,
    spec: OrbitSpec,
    theta: float,
) -> tuple[float, float]:
    local_x = math.cos(theta) * shorter * spec.radius
    local_y = math.sin(theta) * shorter * spec.radius * spec.flattening
    cosine = math.cos(spec.tilt)
    sine = math.sin(spec.tilt)
    return (
        center[0] + local_x * cosine - local_y * sine,
        center[1] + local_x * sine + local_y * cosine,
    )


def _draw_orbit_ribbon(
    image: Image.Image,
    center: tuple[float, float],
    shorter: float,
    spec: OrbitSpec,
    phase: float,
    color: str,
) -> None:
    draw = ImageDraw.Draw(image)
    full_orbit = _orbit_points(center, shorter, spec, 0.0, TAU, 300)
    draw.line(
        [*full_orbit, full_orbit[0]],
        fill=_rgba(color, 31),
        width=max(1, round(shorter * 0.0008)),
        joint="curve",
    )

    trail = _orbit_points(center, shorter, spec, phase - 1.18, phase + 0.06, 84)
    trail_width = max(1, round(shorter * 0.00165))
    for index in range(len(trail) - 1):
        progress = index / (len(trail) - 2)
        alpha = round(12 + 112 * progress**2.2)
        draw.line(
            (trail[index], trail[index + 1]),
            fill=_rgba(color, alpha),
            width=trail_width,
        )

    future = _orbit_points(center, shorter, spec, phase + 0.32, phase + 0.78, 7)
    tick_radius = max(0.7, shorter * 0.00125)
    for index, point in enumerate(future[1:-1], start=1):
        alpha = round(68 * (1.0 - index / len(future)))
        draw.ellipse(
            (
                point[0] - tick_radius,
                point[1] - tick_radius,
                point[0] + tick_radius,
                point[1] + tick_radius,
            ),
            fill=_rgba(color, alpha),
        )


def _ring_points(
    center: tuple[float, float],
    planet_radius: float,
    rotation: float,
    multiplier: float,
    flattening: float,
    start: float,
    end: float,
) -> list[tuple[float, float]]:
    theta = np.linspace(start, end, 120, dtype=np.float32)
    local_x = np.cos(theta) * planet_radius * multiplier
    local_y = np.sin(theta) * planet_radius * flattening
    cosine = math.cos(rotation)
    sine = math.sin(rotation)
    x = center[0] + local_x * cosine - local_y * sine
    y = center[1] + local_x * sine + local_y * cosine
    return [tuple(point) for point in np.column_stack((x, y))]


def _draw_ring_bands(
    image: Image.Image,
    center: tuple[float, float],
    radius: float,
    rotation: float,
    colors: tuple[str, str],
    *,
    front: bool,
) -> None:
    draw = ImageDraw.Draw(image)
    start, end = (0.0, math.pi) if front else (math.pi, TAU)
    bands = (
        (1.46, 0.35, 78),
        (1.58, 0.37, 155),
        (1.72, 0.39, 105),
        (1.88, 0.41, 190),
        (2.02, 0.43, 72),
    )
    for index, (multiplier, flattening, alpha) in enumerate(bands):
        points = _ring_points(
            center,
            radius,
            rotation,
            multiplier,
            flattening,
            start,
            end,
        )
        draw.line(
            points,
            fill=_rgba(colors[index % 2], alpha),
            width=max(1, round(radius * (0.025 if index % 2 else 0.016))),
            joint="curve",
        )


def _draw_world(
    canvas: Image.Image,
    glow: Image.Image,
    center: tuple[float, float],
    diameter: int,
    colors: tuple[str, str],
    light_angle: float,
    texture_phase: float,
    style: int,
    ring_colors: tuple[str, str],
    *,
    ringed: bool,
) -> None:
    glow_draw = ImageDraw.Draw(glow)
    cx, cy = center
    radius = diameter / 2.0
    glow_draw.ellipse(
        (cx - radius * 1.12, cy - radius * 1.12, cx + radius * 1.12, cy + radius * 1.12),
        fill=_rgba(colors[1], 30),
    )

    ring_rotation = -0.34
    if ringed:
        _draw_ring_bands(
            canvas,
            center,
            radius,
            ring_rotation,
            ring_colors,
            front=False,
        )

    sprite = _planet_sprite(diameter, colors, light_angle, texture_phase, style)
    position = (round(cx - diameter / 2.0), round(cy - diameter / 2.0))
    canvas.alpha_composite(sprite, dest=position)

    if ringed:
        _draw_ring_bands(
            canvas,
            center,
            radius,
            ring_rotation,
            ring_colors,
            front=True,
        )


def _planet_group(
    width: int,
    height: int,
    palette: PlanetariumPalette,
    rng: np.random.Generator,
    *,
    outer: bool,
) -> Image.Image:
    work_width, work_height, factor = _working_dimensions(width, height)
    glow = Image.new("RGBA", (work_width, work_height), (0, 0, 0, 0))
    crisp = Image.new("RGBA", (work_width, work_height), (0, 0, 0, 0))
    shorter = min(work_width, work_height)
    system_center = (work_width / 2.0, work_height / 2.0)
    indices = range(3, 6) if outer else range(3)

    for index in indices:
        spec = ORBIT_SPECS[index]
        theta = spec.phase + palette.phase
        _draw_orbit_ribbon(
            crisp,
            system_center,
            shorter,
            spec,
            theta,
            palette.orbit,
        )

    for index in indices:
        spec = ORBIT_SPECS[index]
        theta = spec.phase + palette.phase
        center = _orbit_position(system_center, shorter, spec, theta)
        diameter = max(9, round(shorter * spec.diameter))
        light_angle = math.atan2(
            system_center[1] - center[1],
            system_center[0] - center[0],
        )
        _draw_world(
            crisp,
            glow,
            center,
            diameter,
            palette.worlds[index],
            light_angle,
            palette.phase + index * 1.37,
            index,
            (palette.dust, palette.worlds[index][1]),
            ringed=index == 4,
        )

        if index in {2, 5}:
            moon_theta = theta + 1.1 + float(rng.uniform(-0.12, 0.12))
            moon_distance = diameter * 0.82
            moon_center = (
                center[0] + math.cos(moon_theta) * moon_distance,
                center[1] + math.sin(moon_theta) * moon_distance,
            )
            moon_diameter = max(5, round(diameter * 0.22))
            moon_draw = ImageDraw.Draw(crisp)
            moon_bounds = (
                center[0] - moon_distance,
                center[1] - moon_distance,
                center[0] + moon_distance,
                center[1] + moon_distance,
            )
            moon_draw.arc(
                moon_bounds,
                start=math.degrees(moon_theta) - 95,
                end=math.degrees(moon_theta) + 35,
                fill=_rgba(palette.orbit, 54),
                width=max(1, round(shorter * 0.0007)),
            )
            moon_light = math.atan2(
                system_center[1] - moon_center[1],
                system_center[0] - moon_center[0],
            )
            _draw_world(
                crisp,
                glow,
                moon_center,
                moon_diameter,
                (palette.space[1], palette.orbit),
                moon_light,
                palette.phase + index,
                0,
                (palette.orbit, palette.dust),
                ringed=False,
            )

    softened = glow.filter(ImageFilter.GaussianBlur(max(0.7, shorter * 0.0045)))
    combined = Image.alpha_composite(softened, crisp)
    return _finish_layer(combined, width, height, factor)


def _dust_and_comets(
    width: int,
    height: int,
    palette: PlanetariumPalette,
    rng: np.random.Generator,
) -> Image.Image:
    work_width, work_height, factor = _working_dimensions(width, height)
    glow = Image.new("RGBA", (work_width, work_height), (0, 0, 0, 0))
    crisp = Image.new("RGBA", (work_width, work_height), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    draw = ImageDraw.Draw(crisp)
    shorter = min(work_width, work_height)
    cx, cy = work_width / 2.0, work_height / 2.0

    asteroid_count = max(120, round(shorter * 0.095))
    belt_tilt = -0.16
    cosine = math.cos(belt_tilt)
    sine = math.sin(belt_tilt)
    for asteroid in range(asteroid_count):
        theta = TAU * asteroid / asteroid_count + float(rng.uniform(-0.018, 0.018))
        radius = shorter * float(rng.normal(0.254, 0.0085))
        local_x = math.cos(theta) * radius
        local_y = math.sin(theta) * radius * 0.72
        x = cx + local_x * cosine - local_y * sine
        y = cy + local_x * sine + local_y * cosine
        perspective = 0.65 + 0.35 * (0.5 + 0.5 * math.sin(theta))
        size = max(
            0.55,
            float(rng.uniform(1.0, 4.2)) * shorter / 2600.0 * perspective,
        )
        color = palette.dust if asteroid % 3 else palette.orbit
        draw.ellipse(
            (
                x - size * 1.25,
                y - size * 0.72,
                x + size * 1.25,
                y + size * 0.72,
            ),
            fill=_rgba(color, int(rng.integers(72, 190))),
        )

    for comet in range(3):
        head_theta = palette.phase + comet * TAU / 3.0 + float(rng.uniform(-0.16, 0.16))
        head_radius = shorter * (0.20 + comet * 0.098)
        progress = np.linspace(0.0, 1.0, 74, dtype=np.float32)
        theta = head_theta - (0.52 + comet * 0.08) * progress
        radius = head_radius + shorter * (
            (0.046 + comet * 0.008) * progress + 0.024 * progress * progress
        )
        points = [
            (cx + math.cos(float(a)) * float(r), cy + math.sin(float(a)) * float(r))
            for a, r in zip(theta, radius, strict=True)
        ]
        stroke = max(1, round(shorter * 0.00125))
        glow_draw.line(
            points[::-1],
            fill=_rgba(palette.dust, 76),
            width=stroke * 5,
            joint="curve",
        )
        for index in range(len(points) - 1, 0, -1):
            head_amount = 1.0 - progress[index]
            alpha = round(12 + 196 * head_amount**1.9)
            draw.line(
                (points[index], points[index - 1]),
                fill=_rgba(palette.dust, alpha),
                width=max(1, round(stroke * (0.55 + 0.75 * head_amount))),
            )
        x, y = points[0]
        comet_head_radius = max(1.0, shorter * 0.0028)
        draw.ellipse(
            (
                x - comet_head_radius,
                y - comet_head_radius,
                x + comet_head_radius,
                y + comet_head_radius,
            ),
            fill=_rgba(palette.star[0], 245),
        )

    softened = glow.filter(ImageFilter.GaussianBlur(max(0.7, shorter * 0.006)))
    combined = Image.alpha_composite(softened, crisp)
    return _finish_layer(combined, width, height, factor)


def render_planetarium(
    variant: str,
    width: int,
    height: int,
    layer_index: int,
    rng: np.random.Generator,
) -> Image.Image:
    """Render one layer of the planet-inspired spinner family."""

    palette = PLANETARIUM[variant]
    if layer_index == 0:
        return _planetarium_background(width, height, palette, rng)
    if layer_index == 1:
        return _stellar_frame(width, height, palette, rng)
    if layer_index == 2:
        return _planet_group(width, height, palette, rng, outer=False)
    if layer_index == 3:
        return _planet_group(width, height, palette, rng, outer=True)
    if layer_index == 4:
        return _dust_and_comets(width, height, palette, rng)
    raise ValueError(f"no planetarium layer renderer registered for layer {layer_index}")
