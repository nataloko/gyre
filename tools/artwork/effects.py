"""Reusable procedural treatments for the effect-based designs."""

from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

from .core import center_crop_zoom, image_from_array, rgb_array


def _scaled(image: Image.Image, pixels_at_2600: float, minimum: int = 1) -> int:
    return max(minimum, round(pixels_at_2600 * min(image.size) / 2600.0))


def _outline(image: Image.Image, *, width: int | None = None, opacity: float = 0.9) -> Image.Image:
    width = width or _scaled(image, 9)
    edges = image.convert("RGB").filter(ImageFilter.FIND_EDGES).convert("L")
    edges = edges.filter(ImageFilter.MaxFilter(max(3, width // 2 * 2 + 1)))
    edges = edges.point(lambda value: int(min(255, value * 1.7)))
    black = Image.new("RGB", image.size, "black")
    return Image.composite(black, image.convert("RGB"), edges.point(lambda p: int(p * opacity)))


def _pixelate(image: Image.Image, cells: int, *, smooth: bool = False) -> Image.Image:
    width, height = image.size
    sample_width = max(4, cells)
    sample_height = max(4, round(cells * height / width))
    tiny = image.resize((sample_width, sample_height), Image.Resampling.BOX)
    result = tiny.resize(image.size, Image.Resampling.NEAREST)
    if smooth:
        result = result.filter(ImageFilter.GaussianBlur(_scaled(image, 5)))
    return result


def _dot_matrix(
    image: Image.Image,
    rng: np.random.Generator,
    *,
    spacing_at_2600: int = 34,
    jitter: float = 0.0,
    black_background: bool = True,
) -> Image.Image:
    spacing = _scaled(image, spacing_at_2600, 5)
    source = np.asarray(image.convert("RGB"), dtype=np.uint8)
    background = (0, 0, 0) if black_background else tuple(np.mean(source, axis=(0, 1)).astype(int) // 4)
    out = Image.new("RGB", image.size, background)
    draw = ImageDraw.Draw(out)
    for y in range(spacing // 2, image.height, spacing):
        for x in range(spacing // 2, image.width, spacing):
            jx = int(rng.uniform(-jitter, jitter) * spacing)
            jy = int(rng.uniform(-jitter, jitter) * spacing)
            sx = min(image.width - 1, max(0, x + jx))
            sy = min(image.height - 1, max(0, y + jy))
            color = tuple(int(value) for value in source[sy, sx])
            brightness = sum(color) / (3 * 255)
            radius = max(1.0, spacing * (0.16 + 0.32 * brightness))
            draw.ellipse((x + jx - radius, y + jy - radius, x + jx + radius, y + jy + radius), fill=color)
    return out


def _honeycomb(image: Image.Image, *, radius_at_2600: int) -> Image.Image:
    """Sample ``image`` onto an edge-sharing, point-topped hexagonal lattice."""

    radius = _scaled(image, radius_at_2600, 4)
    source = np.asarray(image.convert("RGB"), dtype=np.uint8)
    # Starting with the source makes sub-pixel rounding at the outermost tile
    # edges harmless. The treatment has no dark grout between its cells;
    # neighbouring hexagons share their edges.
    out = image.convert("RGB").copy()
    draw = ImageDraw.Draw(out)
    half_width = math.sqrt(3.0) * radius / 2.0
    column_step = 2.0 * half_width
    row_step = 1.5 * radius

    row = 0
    y = -2.0 * radius
    while y < image.height + 2.0 * radius:
        x = -2.0 * column_step + (half_width if row % 2 else 0.0)
        while x < image.width + 2.0 * column_step:
            sx = min(image.width - 1, max(0, round(x)))
            sy = min(image.height - 1, max(0, round(y)))
            color = tuple(int(value) for value in source[sy, sx])
            points = [
                (
                    x + radius * math.cos(-math.pi / 2 + math.pi / 3 * k),
                    y + radius * math.sin(-math.pi / 2 + math.pi / 3 * k),
                )
                for k in range(6)
            ]
            draw.polygon(points, fill=color)
            x += column_step
        row += 1
        y += row_step
    return out


def _clip_voronoi_half_plane(
    polygon: list[tuple[float, float]],
    normal_x: float,
    normal_y: float,
    limit: float,
) -> list[tuple[float, float]]:
    """Clip a convex polygon to ``point dot normal <= limit``."""

    if not polygon:
        return []

    clipped: list[tuple[float, float]] = []
    previous = polygon[-1]
    previous_distance = previous[0] * normal_x + previous[1] * normal_y - limit
    previous_inside = previous_distance <= 1e-7

    for current in polygon:
        current_distance = current[0] * normal_x + current[1] * normal_y - limit
        current_inside = current_distance <= 1e-7
        if current_inside != previous_inside:
            amount = previous_distance / (previous_distance - current_distance)
            clipped.append(
                (
                    previous[0] + amount * (current[0] - previous[0]),
                    previous[1] + amount * (current[1] - previous[1]),
                )
            )
        if current_inside:
            clipped.append(current)
        previous = current
        previous_distance = current_distance
        previous_inside = current_inside
    return clipped


def _stained_glass(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    """Create an irregular, dark-grouted Voronoi chip mosaic."""

    spacing = max(4.0, 18.5 * min(image.size) / 2600.0)
    row_step = math.sqrt(3.0) * spacing / 2.0
    jitter = 0.27 * spacing
    padding = 3
    first_row = -padding
    first_column = -padding
    row_count = math.ceil(image.height / row_step) + padding * 2 + 2
    column_count = math.ceil(image.width / spacing) + padding * 2 + 2

    seeds = np.empty((row_count, column_count, 2), dtype=np.float64)
    for row_index in range(row_count):
        row = first_row + row_index
        for column_index in range(column_count):
            column = first_column + column_index
            seeds[row_index, column_index] = (
                (column + 0.5 * (row % 2)) * spacing + rng.uniform(-jitter, jitter),
                row * row_step + rng.uniform(-jitter, jitter),
            )

    source = np.asarray(image.convert("RGB"), dtype=np.uint8)
    out = Image.new("RGB", image.size, "black")
    draw = ImageDraw.Draw(out)
    extent = 2.0 * spacing
    boundaries: list[list[tuple[float, float]]] = []

    for row_index in range(row_count):
        for column_index in range(column_count):
            center_x, center_y = seeds[row_index, column_index]
            if not (
                -spacing <= center_x <= image.width + spacing
                and -spacing <= center_y <= image.height + spacing
            ):
                continue

            # Work in coordinates relative to this seed.  Clipping against the
            # surrounding jittered triangular lattice produces the irregular
            # five-, six-, and seven-sided chips used by the effect.
            polygon = [
                (-extent, -extent),
                (extent, -extent),
                (extent, extent),
                (-extent, extent),
            ]
            for neighbor_row in range(max(0, row_index - 2), min(row_count, row_index + 3)):
                for neighbor_column in range(
                    max(0, column_index - 2), min(column_count, column_index + 3)
                ):
                    if neighbor_row == row_index and neighbor_column == column_index:
                        continue
                    other_x, other_y = seeds[neighbor_row, neighbor_column]
                    delta_x = other_x - center_x
                    delta_y = other_y - center_y
                    limit = (delta_x * delta_x + delta_y * delta_y) / 2.0
                    polygon = _clip_voronoi_half_plane(polygon, delta_x, delta_y, limit)
                    if not polygon:
                        break
                if not polygon:
                    break

            if len(polygon) < 3:
                continue
            points = [(center_x + point_x, center_y + point_y) for point_x, point_y in polygon]
            boundaries.append(points)

            # Some chips are unlit and another portion is dimmed.  The final
            # boundary pass below supplies separate grout, including between
            # two neighboring illuminated chips.
            if rng.random() < 0.31:
                continue
            light = rng.random()
            shade = rng.uniform(0.18, 1.0) if light < 0.40 else rng.uniform(0.98, 1.06)
            sample_x = min(image.width - 1, max(0, round(center_x)))
            sample_y = min(image.height - 1, max(0, round(center_y)))
            color = tuple(int(value) for value in np.clip(source[sample_y, sample_x] * shade, 0, 255))
            draw.polygon(points, fill=color)

    for points in boundaries:
        draw.line([*points, points[0]], fill="black", width=1)

    return out


def _noise(image: Image.Image, rng: np.random.Generator, amount: float, *, monochrome: bool = False) -> Image.Image:
    array = rgb_array(image)
    shape = (*array.shape[:2], 1 if monochrome else 3)
    noise = rng.normal(0.0, amount, shape).astype(np.float32)
    return image_from_array(np.clip(array + noise, 0.0, 1.0))


def _hatch(image: Image.Image, *, colored: bool = True) -> Image.Image:
    array = rgb_array(image)
    height, width = array.shape[:2]
    yy, xx = np.indices((height, width), dtype=np.int32)
    lum = array.mean(axis=2)
    spacing = _scaled(image, 28, 5)
    line_a = ((xx + yy) % spacing) < max(1, spacing // 6)
    line_b = ((xx - yy) % (spacing * 2)) < max(1, spacing // 8)
    mask = line_a | (line_b & (lum < 0.58))
    if colored:
        result = array.copy()
        result[mask] *= 0.2
    else:
        result = np.repeat(lum[..., None], 3, axis=2)
        result[mask] *= 0.08
    return image_from_array(result)


def _newspaper(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    gray = ImageOps.grayscale(image)
    gray_rgb = Image.merge("RGB", (gray, gray, gray))
    return _dot_matrix(gray_rgb, rng, spacing_at_2600=28, jitter=0.05, black_background=False)


def _discontinuities(values: np.ndarray) -> np.ndarray:
    """Return a one-pixel mask wherever neighboring labels or colors differ."""

    mask = np.zeros(values.shape[:2], dtype=bool)
    if values.ndim == 2:
        horizontal = values[:, 1:] != values[:, :-1]
        vertical = values[1:, :] != values[:-1, :]
    else:
        horizontal = np.any(values[:, 1:] != values[:, :-1], axis=2)
        vertical = np.any(values[1:, :] != values[:-1, :], axis=2)
    mask[:, 1:] |= horizontal
    mask[1:, :] |= vertical
    return mask


def _dilate_mask(mask: np.ndarray, width: int) -> np.ndarray:
    width = max(1, width)
    width = width if width % 2 else width + 1
    if width == 1:
        return mask
    mask_image = Image.fromarray(mask.astype(np.uint8) * 255, mode="L")
    return np.asarray(mask_image.filter(ImageFilter.MaxFilter(width))) > 0


def _line_marker(image: Image.Image) -> Image.Image:
    poster = ImageOps.posterize(image.convert("RGB"), 4)
    array = rgb_array(poster)
    height, width = array.shape[:2]
    yy, xx = np.indices((height, width), dtype=np.int32)
    spacing = _scaled(image, 18, 4)
    stroke = ((xx + 3 * yy) % spacing) < max(1, spacing // 7)
    array[stroke] *= 0.63
    return image_from_array(array)


def _gradient_ink_factor(source: np.ndarray) -> np.ndarray:
    values = source.astype(np.float32) / 255.0
    gradient = np.zeros(values.shape[:2], dtype=np.float32)
    gradient[:, 1:-1] += np.sqrt(np.sum((values[:, 2:] - values[:, :-2]) ** 2, axis=2)) / 2.0
    gradient[1:-1, :] += np.sqrt(np.sum((values[2:] - values[:-2]) ** 2, axis=2)) / 2.0
    knots = np.percentile(gradient, [50.0, 75.0, 90.0, 97.0, 99.5])
    levels = np.asarray([0.0, 0.18, 0.38, 0.72, 1.0], dtype=np.float32)
    distinct = np.concatenate(([True], np.diff(knots) > 1e-7))
    knots = knots[distinct]
    levels = levels[distinct]
    if len(knots) == 1:
        return np.ones(gradient.shape, dtype=np.float32)
    ink = np.interp(gradient, knots, levels).astype(np.float32)
    return 1.0 - 0.95 * ink


def _warp_brush_mask(
    mask: np.ndarray,
    image: Image.Image,
    rng: np.random.Generator,
) -> np.ndarray:
    cell = _scaled(image, 26, 4)
    low_width = max(5, math.ceil(image.width / cell))
    low_height = max(5, math.ceil(image.height / cell))

    def field() -> np.ndarray:
        low = np.clip(
            128.0 + rng.normal(0.0, 44.0, (low_height, low_width)),
            0.0,
            255.0,
        ).astype(np.uint8)
        texture = Image.fromarray(low, mode="L").filter(ImageFilter.GaussianBlur(0.8))
        texture = texture.resize(image.size, Image.Resampling.BICUBIC)
        values = np.asarray(texture, dtype=np.float32)
        values -= values.mean()
        return np.clip(values / max(float(values.std()) * 2.2, 1.0), -1.0, 1.0)

    amplitude = _scaled(image, 7, 1)
    shift_x = np.rint(field() * amplitude).astype(np.int16)
    shift_y = np.rint(field() * amplitude).astype(np.int16)
    xx = np.clip(np.arange(image.width, dtype=np.int32)[None, :] + shift_x, 0, image.width - 1)
    yy = np.clip(np.arange(image.height, dtype=np.int32)[:, None] + shift_y, 0, image.height - 1)
    return mask[yy, xx]


def _marker(image: Image.Image, rng: np.random.Generator, *, dry_brush: bool) -> Image.Image:
    """Lay contour-following marker and dry-brush lines over a smooth base."""

    source = image.convert("RGB")
    source_array = np.asarray(source, dtype=np.uint8)

    if dry_brush:
        palette = source.quantize(
            colors=10,
            method=Image.Quantize.FASTOCTREE,
            dither=Image.Dither.NONE,
        )
        coarse_edges = _discontinuities(np.asarray(palette, dtype=np.uint8))
        luminance = ImageOps.posterize(ImageOps.grayscale(source), 6)
        luminance_edges = _discontinuities(np.asarray(luminance, dtype=np.uint8))
        black = _dilate_mask(coarse_edges, _scaled(image, 17, 3)) | luminance_edges
        black = _warp_brush_mask(black, image, rng)
        result = ImageEnhance.Color(ImageOps.posterize(source, 4)).enhance(1.28)
        result = ImageEnhance.Contrast(result).enhance(1.08)
    else:
        palette = source.quantize(
            colors=12,
            method=Image.Quantize.FASTOCTREE,
            dither=Image.Dither.NONE,
        )
        coarse_edges = _discontinuities(np.asarray(palette, dtype=np.uint8))
        luminance = ImageOps.posterize(ImageOps.grayscale(source), 6)
        luminance_edges = _discontinuities(np.asarray(luminance, dtype=np.uint8))
        black = luminance_edges | _dilate_mask(coarse_edges, _scaled(image, 7, 3))
        result = ImageEnhance.Color(source).enhance(1.08)

    output = np.asarray(result, dtype=np.uint8).copy()
    if not dry_brush:
        output = np.clip(output * _gradient_ink_factor(source_array)[..., None], 0, 255).astype(np.uint8)
    output[black] = 0
    return Image.fromarray(output, mode="RGB")


def _splinter(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    source = np.asarray(image.convert("RGB"), dtype=np.uint8)
    output = np.empty_like(source)
    minimum = _scaled(image, 85, 4)
    maximum = max(minimum + 1, _scaled(image, 245, 8))
    maximum_shift = _scaled(image, 40, 2)

    top = 0
    while top < image.height:
        tile_height = int(rng.integers(minimum, maximum + 1))
        bottom = min(image.height, top + tile_height)
        left = 0
        while left < image.width:
            if rng.random() < 0.16:
                tile_width = int(rng.integers(max(2, minimum // 3), minimum + 1))
            else:
                tile_width = int(rng.integers(minimum, maximum + 1))
            right = min(image.width, left + tile_width)
            shift_x = int(rng.integers(-maximum_shift, maximum_shift + 1))
            shift_y = int(rng.integers(-maximum_shift, maximum_shift + 1))
            source_x = np.clip(np.arange(left, right) + shift_x, 0, image.width - 1)
            source_y = np.clip(np.arange(top, bottom) + shift_y, 0, image.height - 1)
            output[top:bottom, left:right] = source[source_y[:, None], source_x[None, :]]
            left = right
        top = bottom
    return Image.fromarray(output, mode="RGB")


def _mirror(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    source = np.asarray(image.convert("RGB"), dtype=np.uint8)
    output = np.empty_like(source)
    strip = _scaled(image, 65, 8)
    for start in range(0, image.height, strip):
        end = min(image.height, start + strip)
        shift = int(rng.integers(-strip * 2, strip * 2 + 1))
        output[start:end] = np.roll(source[start:end], shift, axis=1)
    result = Image.fromarray(output, mode="RGB")
    draw = ImageDraw.Draw(result)
    for _ in range(max(18, image.width // max(1, _scaled(image, 95)))):
        edge = int(rng.integers(0, 4))
        color = (15, 15, 15)
        width = _scaled(image, 4)
        if edge < 2:
            y = int(rng.integers(0, image.height))
            draw.line((0, y, image.width, int(rng.integers(0, image.height))), fill=color, width=width)
        else:
            x = int(rng.integers(0, image.width))
            draw.line((x, 0, int(rng.integers(0, image.width)), image.height), fill=color, width=width)
    return result


def _blunt_knife(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    """Round and subtly roughen hard palette boundaries without color bleed."""

    source = np.asarray(image.convert("RGB"), dtype=np.uint8)
    width, height = image.size
    field_width = max(5, math.ceil(width / 150))
    field_height = max(5, math.ceil(height / 150))

    def displacement_field() -> np.ndarray:
        low = np.clip(
            128.0 + rng.normal(0.0, 42.0, (field_height, field_width)),
            0.0,
            255.0,
        ).astype(np.uint8)
        field = Image.fromarray(low, mode="L").filter(ImageFilter.GaussianBlur(1.1))
        field = field.resize(image.size, Image.Resampling.BICUBIC)
        values = np.asarray(field, dtype=np.float32)
        values -= values.mean()
        deviation = max(float(values.std()), 1.0)
        return np.clip(values / (2.3 * deviation), -1.0, 1.0)

    amplitude = _scaled(image, 7, 1)
    shift_x = np.rint(displacement_field() * amplitude).astype(np.int16)
    shift_y = np.rint(displacement_field() * amplitude).astype(np.int16)
    xx = np.clip(np.arange(width, dtype=np.int32)[None, :] + shift_x, 0, width - 1)
    yy = np.clip(np.arange(height, dtype=np.int32)[:, None] + shift_y, 0, height - 1)
    warped = Image.fromarray(source[yy, xx], mode="RGB")

    palette = warped.quantize(
        colors=12,
        method=Image.Quantize.FASTOCTREE,
        dither=Image.Dither.NONE,
    )
    filter_size = _scaled(image, 15, 3)
    filter_size = filter_size if filter_size % 2 else filter_size + 1
    return palette.filter(ImageFilter.ModeFilter(filter_size)).convert("RGB")


def _sprayed(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    noisy = _noise(image, rng, 0.07)
    array = rgb_array(noisy)
    height, width = array.shape[:2]
    specks = rng.random((height, width), dtype=np.float32)
    array[specks < 0.035] *= 0.15
    array[specks > 0.985] = np.minimum(1.0, array[specks > 0.985] * 1.35)
    return image_from_array(array).filter(ImageFilter.UnsharpMask(_scaled(image, 3), 140, 2))


def _vignette(image: Image.Image, strength: float = 0.82) -> Image.Image:
    array = rgb_array(image)
    y = np.linspace(-1.0, 1.0, image.height, dtype=np.float32)[:, None]
    x = np.linspace(-1.0, 1.0, image.width, dtype=np.float32)[None, :]
    radius = np.sqrt(x * x + y * y)
    factor = np.clip(1.0 - strength * np.maximum(radius - 0.18, 0.0) ** 1.45, 0.08, 1.0)
    return image_from_array(array * factor[..., None])


def apply_effect(
    image: Image.Image,
    effect: str,
    rng: np.random.Generator,
    *,
    design_id: str | None = None,
) -> Image.Image:
    """Apply a named catalog effect to an opaque RGB base image."""

    name = effect.lower()
    if name in {"base", "default"}:
        return image.convert("RGB")

    wants_outline = "outline" in name
    name = name.replace("outline", "")

    if name in {"blur", "blursmall"}:
        result = image.filter(ImageFilter.GaussianBlur(_scaled(image, 38)))
    elif name in {"blurlarge", "blurlargev2", "blurstrong"}:
        result = image.filter(ImageFilter.GaussianBlur(_scaled(image, 105)))
    elif name in {"sharp", "sharpen"}:
        result = image.filter(ImageFilter.UnsharpMask(_scaled(image, 16), 230, 2))
    elif name in {"posterize", "posteredges"}:
        result = ImageOps.posterize(image.convert("RGB"), 3)
        if name == "posteredges":
            result = _outline(result, opacity=0.65)
    elif name in {"crystalize", "burst"}:
        result = _pixelate(image, 54 if name == "crystalize" else 34)
    elif name == "honeycomb":
        result = _honeycomb(image, radius_at_2600=24)
    elif name == "stainedglass":
        result = _stained_glass(image, rng)
    elif name == "dotmatrix":
        result = _dot_matrix(image, rng, spacing_at_2600=31)
    elif name == "pointillize":
        result = _dot_matrix(image, rng, spacing_at_2600=70, jitter=0.34)
    elif name in {"lines", "linesmedium"}:
        result = _line_marker(image)
    elif name == "marker":
        result = _marker(image, rng, dry_brush=design_id == "spinner_7")
    elif name == "splinter":
        result = _splinter(image, rng)
    elif name == "mirror":
        result = _mirror(image, rng)
    elif name in {"sprayedstrokes", "stroked"}:
        result = _sprayed(image, rng)
        if name == "stroked":
            result = ImageEnhance.Color(result).enhance(1.08)
    elif name == "noise":
        result = _noise(image, rng, 0.105)
    elif name == "sandpaper":
        result = _noise(image, rng, 0.16, monochrome=True).filter(ImageFilter.GaussianBlur(_scaled(image, 1)))
    elif name == "newspaper":
        result = _newspaper(image, rng)
    elif name == "hatched":
        result = _hatch(image, colored=True)
    elif name == "paletteknife":
        result = image.filter(ImageFilter.GaussianBlur(_scaled(image, 19)))
        result = ImageOps.posterize(result, 4)
        result = result.filter(ImageFilter.UnsharpMask(_scaled(image, 20), 190, 4))
    elif name == "bluntknife":
        result = _blunt_knife(image, rng)
    elif name in {"zoom", "zoomsmall"}:
        scale = 1.62 if name == "zoom" else 1.27
        result = center_crop_zoom(image, scale, image.size)
    elif name == "vignette":
        result = _vignette(image)
    else:
        result = image.convert("RGB")

    if wants_outline:
        result = _outline(result)
    return result.convert("RGB")
