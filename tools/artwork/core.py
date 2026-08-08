"""Low-level deterministic raster helpers."""

from __future__ import annotations

import colorsys
import hashlib
import math
from pathlib import Path
from typing import Sequence

import numpy as np
from PIL import Image


RGB = tuple[int, int, int]


def parse_color(value: str | Sequence[int]) -> np.ndarray:
    if isinstance(value, str):
        clean = value.lstrip("#")
        if len(clean) != 6:
            raise ValueError(f"expected #rrggbb color, got {value!r}")
        return np.asarray(tuple(int(clean[i : i + 2], 16) for i in (0, 2, 4)), dtype=np.float32) / 255.0
    return np.asarray(value, dtype=np.float32) / 255.0


def stable_seed(seed: int, *parts: object) -> int:
    payload = "\0".join([str(seed), *(str(part) for part in parts)]).encode("utf-8")
    return int.from_bytes(hashlib.sha256(payload).digest()[:8], "big", signed=False)


def rng_for(seed: int, *parts: object) -> np.random.Generator:
    return np.random.default_rng(stable_seed(seed, *parts))


def coordinate_grid(
    width: int,
    height: int,
    *,
    center: tuple[float, float] = (0.5, 0.5),
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Return normalized x/y, radius and angle as float32 arrays.

    The shorter image side spans -1..1.  This keeps procedural geometry stable
    for the two catalog layers whose transparent bounds are slightly cropped.
    """

    scale = np.float32(2.0 / min(width, height))
    x_axis = (np.arange(width, dtype=np.float32) + np.float32(0.5) - width * center[0]) * scale
    y_axis = (np.arange(height, dtype=np.float32) + np.float32(0.5) - height * center[1]) * scale
    x, y = np.meshgrid(x_axis, y_axis)
    radius = np.hypot(x, y, dtype=np.float32)
    angle = np.arctan2(y, x, dtype=np.float32)
    return x, y, radius, angle


def hsv_image(hue: np.ndarray, saturation: np.ndarray | float, value: np.ndarray | float) -> np.ndarray:
    """Vectorized HSV to RGB conversion returning float32 RGB."""

    h = np.mod(hue, 1.0).astype(np.float32, copy=False)
    s = np.broadcast_to(np.asarray(saturation, dtype=np.float32), h.shape)
    v = np.broadcast_to(np.asarray(value, dtype=np.float32), h.shape)
    sector = np.floor(h * 6.0).astype(np.int16)
    fraction = h * 6.0 - sector
    p = v * (1.0 - s)
    q = v * (1.0 - s * fraction)
    t = v * (1.0 - s * (1.0 - fraction))
    sector %= 6
    out = np.empty((*h.shape, 3), dtype=np.float32)
    choices = (
        (v, t, p),
        (q, v, p),
        (p, v, t),
        (p, q, v),
        (t, p, v),
        (v, p, q),
    )
    for index, channels in enumerate(choices):
        mask = sector == index
        for channel, values in enumerate(channels):
            out[..., channel][mask] = values[mask]
    return out


def palette_image(labels: np.ndarray, palette: Sequence[str | Sequence[int]]) -> np.ndarray:
    colors = np.stack([parse_color(color) for color in palette], axis=0)
    return colors[np.mod(labels, len(colors))]


def blend(a: np.ndarray, b: np.ndarray, amount: np.ndarray | float) -> np.ndarray:
    factor = np.asarray(amount, dtype=np.float32)
    if factor.ndim == 2:
        factor = factor[..., None]
    return a * (1.0 - factor) + b * factor


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    t = np.clip((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def rgb_array(image: Image.Image) -> np.ndarray:
    return np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0


def rgba_array(image: Image.Image) -> np.ndarray:
    return np.asarray(image.convert("RGBA"), dtype=np.float32) / 255.0


def image_from_array(array: np.ndarray, mode: str = "RGB") -> Image.Image:
    clipped = np.clip(array * 255.0 + 0.5, 0, 255).astype(np.uint8)
    return Image.fromarray(clipped, mode=mode)


def solid_image(size: tuple[int, int], color: str, mode: str = "RGB") -> Image.Image:
    rgb = tuple(int(round(channel * 255)) for channel in parse_color(color))
    if mode == "RGBA":
        return Image.new(mode, size, (*rgb, 255))
    return Image.new(mode, size, rgb)


def rgb_to_hsv(color: str) -> tuple[float, float, float]:
    rgb = parse_color(color)
    return colorsys.rgb_to_hsv(float(rgb[0]), float(rgb[1]), float(rgb[2]))


def lighten(color: str, amount: float) -> str:
    rgb = parse_color(color)
    mixed = rgb + (1.0 - rgb) * amount
    values = np.clip(mixed * 255 + 0.5, 0, 255).astype(np.uint8)
    return "#" + "".join(f"{int(value):02x}" for value in values)


def darken(color: str, amount: float) -> str:
    rgb = parse_color(color) * (1.0 - amount)
    values = np.clip(rgb * 255 + 0.5, 0, 255).astype(np.uint8)
    return "#" + "".join(f"{int(value):02x}" for value in values)


def center_crop_zoom(image: Image.Image, scale: float, output_size: tuple[int, int]) -> Image.Image:
    if not math.isclose(scale, 1.0):
        scaled = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.Resampling.LANCZOS,
        )
    else:
        scaled = image
    target_width, target_height = output_size
    left = (scaled.width - target_width) // 2
    top = (scaled.height - target_height) // 2
    if scaled.width >= target_width and scaled.height >= target_height:
        return scaled.crop((left, top, left + target_width, top + target_height))
    canvas = Image.new(scaled.mode, output_size, (0, 0, 0, 0) if scaled.mode == "RGBA" else (0, 0, 0))
    canvas.paste(scaled, ((target_width - scaled.width) // 2, (target_height - scaled.height) // 2), scaled if scaled.mode == "RGBA" else None)
    return canvas


def save_image_atomic(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.stem}.tmp{path.suffix}")
    suffix = path.suffix.lower()
    if suffix == ".webp":
        kwargs = {"format": "WEBP", "method": 6, "quality": 94}
        if image.mode == "RGBA":
            kwargs["lossless"] = True
        image.save(temporary, **kwargs)
    elif suffix == ".png":
        image.save(temporary, format="PNG", optimize=True, compress_level=8)
    else:
        raise ValueError(f"unsupported image extension: {suffix}")
    temporary.replace(path)


def alpha_composite_center(base: Image.Image, overlay: Image.Image) -> Image.Image:
    result = base.convert("RGBA")
    layer = overlay.convert("RGBA")
    position = ((result.width - layer.width) // 2, (result.height - layer.height) // 2)
    result.alpha_composite(layer, dest=position)
    return result

