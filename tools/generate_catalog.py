# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "numpy==2.3.2",
#   "pillow==11.3.0",
# ]
# ///
"""Generate Gyre's catalogue of layered generative artwork.

Every piece is maths, not pixels. The renderers live in `tools/artwork/` — ten
generative systems (reaction-diffusion, strange attractors, quasicrystal interference,
recursive Truchet tilings, Kleinian limit sets, and five shaded-spiral scenes) plus
fourteen procedural spinner families. This script owns everything catalogue-shaped:
which designs exist, their layers and rotations, their variants and colours, and the
runtime JSON the app parses.

Unlike the earlier mask-and-ramp catalogue, a layer here is a resolved colour image:
the renderers shade, light and composite in ways a single ramp lookup cannot express,
so each colour variant carries its own pixels and the layer entries have no `ramp`.
The dark twin of a variant is the same render held down to a tone that suits a dark
theme, which keeps the `Label` / `Label (Dark)` pairing `automaticDarkVariants` needs.

Runs in two phases on purpose. The metadata is emitted and checked first, because the
interface draws its accents from these palettes and has to stay legible on a
near-black chassis; finding that out after a 120-variant render would waste the
render.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import threading
import tomllib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import gyre_palette as palette  # noqa: E402
from artwork.catalog import load_catalog  # noqa: E402
from artwork.core import rng_for  # noqa: E402
from artwork.spirals import (  # noqa: E402
    AFTERGLOW,
    ORBIT_GARDEN,
    SPIRAL_DESIGN_IDS,
    PRISMATA,
    TAFFY,
    render_spiral,
)
from artwork.outliers import (  # noqa: E402
    ATTRACTOR,
    KLEINIAN,
    QUASICRYSTAL,
    REACTOR,
    TRUCHET,
    render_outlier,
)
from artwork.circle_limits import (  # noqa: E402
    CIRCLE_LIMITS,
    CIRCLE_LIMIT_DESIGN_IDS,
    render_circle_limit,
)
from artwork.gyre import (  # noqa: E402
    GYRE,
    GYRE_DESIGN_IDS,
    render_gyre,
)
from artwork.planetarium import PLANETARIUM  # noqa: E402

# Every random choice in the renderers flows from this and the identifiers in
# designs.toml, so the whole catalogue reproduces byte for byte.
SEED = 0

# Matches MotionMath.SPIN_WRAP_TURNS = 4, which BundledCatalogRepository checks: the
# scene turns by the user's spin times each design's scaler, and a wrap is only
# invisible while the product is a whole number.
SPIN_WRAP_TURNS = 4

MASTER_EDGE = 2048
THUMB_EDGE = 260
THUMB_QUALITY = 90
LAYER_QUALITY = 94

# SceneRendererTest's reference frames. Four remixes covering a single opaque layer,
# a five-deep stack of transparent overlays, counter-rotating lattices, and line art
# over an opaque weave.
FIXTURE_EDGE = 1744
FIXTURE_REMIXES = (
    "afterglow_hue_biolume",
    "planetarium_hue_golden",
    "quasicrystal_hue_penrose",
    "truchet_hue_circuit",
)

# How far a variant's colours are held down for its dark twin. The same factor is
# applied to the palette seeds, so the chrome accents keep following the artwork.
NIGHT_DIM = 0.45

# SceneTone.DARK_TEXT_CROSSOVER: where black and white text have equal WCAG contrast.
DARK_TONE_CROSSOVER = 0.179

#: Below this mean difference between a piece posed one way and the other, nothing is
#: moving.
MOTION_FLOOR = 0.01

#: How far to pose a layer either way when asking whether its animation shows, in
#: fractions of a turn. Two spreads, because a single one has a blind spot: a piece
#: with n-fold rotational symmetry aliases whenever the spread is a multiple of its
#: symmetry step — Indra's Nest at five mirrors matched itself exactly a fifth of a
#: turn apart and read as static. No modest n aliases both of these at once, and a
#: piece fine enough to alias both really is invisible in motion.
MOTION_POSES = (0.1, 0.045)


# --------------------------------------------------------------------------------------------
# Palettes — the colours a variant paints with, read back off its renderer's recipe
# --------------------------------------------------------------------------------------------


def variant_palette(family: str, key: str) -> list[str]:
    """The hex colours a variant is built from, in no particular order.

    These are inputs, not measurements: with procedural artwork the colours are known
    before anything is drawn, which is what lets the palette legibility be settled in
    the metadata phase instead of after a render.
    """
    match family:
        case "spinner_afterglow":
            return list(AFTERGLOW[key])
        case "spinner_taffy":
            return list(TAFFY[key])
        case "spinner_prismata":
            colors, seam = PRISMATA[key]
            return [*colors, seam]
        case "spinner_orbitgarden":
            return list(ORBIT_GARDEN[key])
        case "spinner_planetarium":
            scene = PLANETARIUM[key]
            return [*scene.space, scene.nebula, *scene.star, scene.orbit, scene.dust]
        case "spinner_reactor":
            return list(REACTOR[key].colors)
        case "spinner_strangeloop":
            return list(ATTRACTOR[key].colors)
        case "spinner_quasicrystal":
            return list(QUASICRYSTAL[key].colors)
        case "spinner_truchet":
            recipe = TRUCHET[key]
            return [*recipe.colors, recipe.jewel]
        case "spinner_kleinian":
            return list(KLEINIAN[key].colors)
        case "spinner_circlelimit":
            # The ink and the accent, but not the paper: the surround is not what the piece
            # is coloured with, and on a phone the framing keeps it almost entirely off screen.
            recipe = CIRCLE_LIMITS[key]
            return [*recipe.colors, recipe.ink, recipe.accent]
        case "spinner_gyre":
            # The paper counts here, unlike the circle limits above. There it is a surround
            # the framing cuts away; here it is layer 0, a plate of flat colour under the
            # whole scene, so it is most of what the chrome has to stay legible against.
            recipe = GYRE[key]
            return [recipe.paper, recipe.ink, recipe.accent]
        case other:
            raise ValueError(f"No palette source for family: {other}")


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    clean = value.lstrip("#")
    return tuple(int(clean[index : index + 2], 16) for index in (0, 2, 4))  # type: ignore[return-value]


def dimmed(rgb: tuple[int, int, int], amount: float) -> tuple[int, int, int]:
    return tuple(round(channel * amount) for channel in rgb)  # type: ignore[return-value]


def _desaturated(rgb: tuple[int, int, int]) -> tuple[int, int, int]:
    hue, saturation, lightness = palette.to_hsl(rgb)
    return palette.from_hsl(hue, saturation * 0.45, lightness)


def palette_colors(hexes: list[str], night: bool = False) -> dict[str, int]:
    """`PaletteColors` for a variant, guaranteed legible before anything is drawn.

    The seeds are ranked by measured luminance rather than by position, because the
    families order their palettes differently — some run dark to light, some are a
    recipe's field list. They are then lifted through the same arithmetic the
    interface will apply, so a variant cannot reach `ArtworkColorSchemeTest` having
    never been checked.
    """
    rgbs = [hex_to_rgb(value) for value in hexes]
    if night:
        rgbs = [dimmed(rgb, NIGHT_DIM) for rgb in rgbs]
    ordered = sorted(rgbs, key=palette.relative_luminance)
    darkest, brightest = ordered[0], ordered[-1]
    middle = ordered[1:-1] or ordered
    vibrant = max(middle, key=lambda rgb: palette.to_hsl(rgb)[1])
    return {
        "loadingColor": palette.to_argb(darkest),
        "vibrantColor": palette.to_argb(palette.legible_seed(vibrant)),
        "lightVibrantColor": palette.to_argb(palette.legible_seed(brightest)),
        "darkVibrantColor": palette.to_argb(palette.legible_seed(vibrant)),
        "mutedColor": palette.to_argb(palette.legible_seed(_desaturated(vibrant))),
        "darkMutedColor": palette.to_argb(palette.legible_seed(_desaturated(darkest))),
    }


# --------------------------------------------------------------------------------------------
# Building the catalogue
# --------------------------------------------------------------------------------------------


def check_scaler_wraps(design_id: str, scaler: float) -> None:
    turns = scaler * SPIN_WRAP_TURNS
    if abs(turns - round(turns)) > 1e-6:
        raise ValueError(
            f"{design_id} has inputRotationScaler {scaler:g}, which is "
            f"{turns:g} turns over the spin wrap; only multiples of 0.25 wrap invisibly",
        )


def build_catalog(definitions: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Phase one: the whole catalogue as metadata, before anything is rendered.

    Ten designs come from `designs.toml`; fourteen come from
    `spinner_designs.json`, whose variants already carry runtime-shaped layers and
    palette colours. Their stable variant ids and layer keys seed the renderers' rng.
    """
    designs: list[dict[str, Any]] = []
    remixes: list[dict[str, Any]] = []
    for design in definitions["design"]:
        check_scaler_wraps(design["id"], design["scaler"])
        variant_ids: list[str] = []
        for variant in design["variant"]:
            hexes = variant_palette(design["family"], variant["palette"])
            for night in (False, True):
                remix_id = f"{design['id']}_hue_{variant['slug']}" + ("_dark" if night else "")
                remixes.append(
                    {
                        "id": remix_id,
                        "designId": design["id"],
                        "label": variant["label"] + (" (Dark)" if night else ""),
                        "family": design["family"],
                        "palette": variant["palette"],
                        "night": night,
                        "layers": design["layer"],
                        "scaler": design["scaler"],
                        "colors": palette_colors(hexes, night),
                    },
                )
                variant_ids.append(remix_id)
        designs.append(
            {
                "id": design["id"],
                "label": design["label"],
                "previewRemixId": f"{design['id']}_hue_{design['variant'][0]['slug']}",
                "remixIds": variant_ids,
            },
        )

    source = definitions["spinnerDesigns"]
    for design in source["designs"]:
        designs.append(
            {
                "id": design["id"],
                "label": design["label"],
                "previewRemixId": design["previewRemixId"],
                "remixIds": list(design["remixIds"]),
            },
        )
    remixes_by_id = {remix["id"]: remix for remix in source["remixes"]}
    for design in source["designs"]:
        for remix_id in design["remixIds"]:
            entry = remixes_by_id[remix_id]
            check_scaler_wraps(entry["designId"], entry["inputRotationScaler"])
            remixes.append(
                {
                    "id": entry["id"],
                    "designId": entry["designId"],
                    "label": entry["label"],
                    "layers": entry["layers"],
                    "scaler": entry["inputRotationScaler"],
                    "colors": entry["colors"],
                    "spinnerDesign": True,
                },
            )
    return designs, remixes


# --------------------------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------------------------


def render_layer_image(design: dict[str, Any], layer_index: int, palette_key: str, size: int):
    """Render one layer of one variant at [size] with stable rng keys."""
    family = design["family"]
    source_remix = f"{family}_color_{palette_key}"
    if family in SPIRAL_DESIGN_IDS:
        source = design["layer"][layer_index]["source"]
        rng = rng_for(SEED, source_remix, layer_index, source)
        return render_spiral(family, palette_key, size, size, layer_index, rng)
    if family in CIRCLE_LIMIT_DESIGN_IDS:
        # Keyed like a spiral because this renderer takes the generator rather than a seed.
        # It spends it on three phases of a paper grain; the tiling itself is exact.
        source = design["layer"][layer_index]["source"]
        rng = rng_for(SEED, source_remix, layer_index, source)
        return render_circle_limit(palette_key, size, size, rng)
    if family in GYRE_DESIGN_IDS:
        # No rng key at all: alone among the renderers this one makes no random choice, so
        # the layer is settled by its palette and its depth and there is nothing to seed.
        return render_gyre(palette_key, size, size, layer_index)
    return render_outlier(family, palette_key, size, size, layer_index, SEED)


def night_image(image):
    """The same render held down to a tone that suits a dark theme."""
    from PIL import Image

    pixels = np.asarray(image, dtype=np.float32)
    if image.mode == "RGBA":
        pixels = pixels.copy()
        pixels[..., :3] *= NIGHT_DIM
    else:
        pixels = pixels * NIGHT_DIM
    # Pillow reads the mode from the array's shape; naming it as well is deprecated.
    return Image.fromarray((pixels + 0.5).astype(np.uint8))


def compose(images) -> Any:
    """The scene as the renderer will draw it: layers alpha-blended in order.

    Overlays are centred on the base layer's canvas, because two overlays use a
    trimmed aspect rather than square; their few-hundredths offset
    (`imageSubsetLayoutParams`) is invisible at thumb size.
    """
    from PIL import Image

    canvas = Image.new("RGBA", images[0].size, (0, 0, 0, 255))
    for image in images:
        layer = image.convert("RGBA")
        canvas.alpha_composite(
            layer,
            dest=((canvas.width - layer.width) // 2, (canvas.height - layer.height) // 2),
        )
    return canvas


def encode_layer(image) -> bytes:
    """A layer as WebP, lossless or not depending on what wins.

    The flat-colour families (Truchet, Prismata) compress better and ring less
    losslessly; the photographic ones (Afterglow, the attractors) are the other way
    round by a large factor. Trying both and keeping the smaller settles it per layer
    instead of per guess.
    """
    lossless = io.BytesIO()
    image.save(lossless, format="WEBP", lossless=True, method=6)
    lossy = io.BytesIO()
    image.save(lossy, format="WEBP", quality=LAYER_QUALITY, method=6)
    return min(lossless.getvalue(), lossy.getvalue(), key=len)


def encode_thumb(composite) -> bytes:
    from PIL import Image

    flattened = Image.new("RGB", composite.size, (0, 0, 0))
    flattened.paste(composite, mask=composite.getchannel("A"))
    thumb = flattened.resize((THUMB_EDGE, THUMB_EDGE), Image.Resampling.LANCZOS)
    buffer = io.BytesIO()
    thumb.save(buffer, format="WEBP", quality=THUMB_QUALITY, method=6)
    return buffer.getvalue()


def scene_tone(thumb_bytes: bytes) -> float:
    """The mean tone SceneTone would measure, as WCAG relative luminance."""
    from PIL import Image

    with Image.open(io.BytesIO(thumb_bytes)) as image:
        mean = np.asarray(image.convert("RGB"), dtype=np.float32).reshape(-1, 3).mean(axis=0)
    return palette.relative_luminance(tuple(round(float(channel)) for channel in mean))  # type: ignore[arg-type]


def rotation_sign(layer: dict[str, Any]) -> float:
    """The direction of a layer's catalogued rotation: ±1, or 0 for a static layer.

    TOML layers state it as a signed period in seconds; JSON layers use the runtime
    `{direction, time}` object.
    """
    rotation = layer.get("rotation")
    if not rotation:
        return 0.0
    if isinstance(rotation, dict):
        return -1.0 if rotation["direction"] == "anticlockwise" else 1.0
    return -1.0 if float(rotation) < 0 else 1.0


def check_animation_shows(design: dict[str, Any], layer_images) -> None:
    """Refuse a piece whose rotation cannot be seen.

    A layer with a `rotation` in the catalogue is a promise that the scene moves, and
    for a rotationally symmetric one that promise is empty. The wallpaper looks
    static, and every control that acts on the animation — Reverse most obviously,
    but Speed too — appears broken, because there is nothing for them to change.

    Posed a fraction of a turn each way, a piece that moves differs from itself. The
    comparison stays inside the artwork's inscribed circle, which is both what the
    viewer is guaranteed to see and the region a raster rotation leaves undisturbed.
    """
    if not any(rotation_sign(layer) for layer in design["layer"]):
        return
    from PIL import Image

    edge = 256
    small = [
        image.convert("RGBA").resize((edge, edge), Image.Resampling.LANCZOS)
        for image in layer_images
    ]
    rows, columns = np.mgrid[0:edge, 0:edge].astype(np.float32)
    disc = np.hypot(rows - (edge - 1) / 2, columns - (edge - 1) / 2) <= edge / 2

    def posed(pose: float, direction: float) -> np.ndarray:
        canvas = Image.new("RGBA", (edge, edge), (0, 0, 0, 255))
        for spec, image in zip(design["layer"], small):
            sign = rotation_sign(spec)
            if sign:
                image = image.rotate(
                    direction * pose * 360.0 * sign,
                    resample=Image.Resampling.BILINEAR,
                )
            canvas.alpha_composite(image)
        return np.asarray(canvas, dtype=np.float32)[..., :3] / 255.0

    moved = max(
        float(np.abs(posed(pose, 1.0) - posed(pose, -1.0)).mean(axis=-1)[disc].mean())
        for pose in MOTION_POSES
    )
    if moved < MOTION_FLOOR:
        raise ValueError(
            f"{design['id']} is symmetric about its own rotation, so its animation cannot "
            f"be seen (posed either way it differs by {moved:.4f})",
        )


# --------------------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------------------


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def store(artwork: Path, data: bytes) -> tuple[str, str]:
    """Write [data] content-addressed under [artwork], returning (asset path, digest).

    The temporary name carries the thread id: two workers can arrive with the same
    digest — identical bytes, by construction — and a shared temporary would let one
    rename the other's half-written file.
    """
    digest = sha256_bytes(data)
    path = artwork / f"{digest}.webp"
    if not path.exists():
        temporary = path.with_name(f".{digest}.{threading.get_ident()}.tmp")
        temporary.write_bytes(data)
        temporary.replace(path)
    return f"assets/artwork/{digest}.webp", digest


def runtime_remix(remix: dict[str, Any]) -> dict[str, Any]:
    """A remix in the shape `model/Catalog.kt` reads.

    The layers carry no `ramp`: they are resolved colour, and the renderer draws a
    rampless texture as-is. `parallaxOnlyOnTilt` follows the rotating layers the way
    the previous catalogue's animated layers did. A spinner design's layers are
    already runtime-shaped in `spinner_designs.json` — rotation, parallax and any
    `imageSubsetLayoutParams` pass through with only the image path replaced.
    """
    layers = []
    for spec, image_url in zip(remix["layers"], remix["_layerPaths"]):
        if remix.get("spinnerDesign"):
            entry = {
                key: spec[key]
                for key in (
                    "parallaxScale",
                    "parallaxOnlyOnTilt",
                    "rotation",
                    "type",
                    "imageSubsetLayoutParams",
                )
                if key in spec
            }
            entry["imageUrl"] = image_url
            layers.append(entry)
            continue
        rotation = spec.get("rotation")
        entry = {
            "imageUrl": image_url,
            "parallaxScale": spec.get("parallax", 0.0),
        }
        if rotation:
            entry.update(
                {
                    "type": "animated",
                    "parallaxOnlyOnTilt": True,
                    "rotation": {
                        "time": abs(float(rotation)),
                        "direction": "anticlockwise" if rotation < 0 else "clockwise",
                    },
                },
            )
        else:
            entry["type"] = "parallax"
        layers.append(entry)
    return {
        "id": remix["id"],
        "designId": remix["designId"],
        "label": remix["label"],
        "isDark": remix.get("_isDark", False),
        "isMultilayered": len(layers) > 1,
        "inputRotationScaler": remix["scaler"],
        "type": "parallax",
        "previews": {"thumb": remix.get("_thumb", "")},
        "layers": layers,
        "colors": remix["colors"],
    }


def write_catalog(
    out: Path,
    designs: list[dict[str, Any]],
    remixes: list[dict[str, Any]],
    records: dict[str, dict[str, Any]],
) -> None:
    runtime = [runtime_remix(remix) for remix in remixes]
    catalog = {
        "designIds": [design["id"] for design in designs],
        "remixIds": [remix["id"] for remix in runtime],
        "designs": designs,
        "remixes": runtime,
    }
    catalog_directory = out / "catalog"
    catalog_directory.mkdir(parents=True, exist_ok=True)
    _atomic_json(catalog_directory / "catalog.json", catalog)
    _atomic_json(
        catalog_directory / "checksums.json",
        {
            "counts": {
                "designs": len(designs),
                "remixes": len(runtime),
                "layers": sum(len(remix["layers"]) for remix in runtime),
                "uniqueAssetFiles": len(records),
            },
            "assets": sorted(records.values(), key=lambda record: record["assetPath"]),
        },
    )


def write_metadata_only(
    out: Path,
    designs: list[dict[str, Any]],
    remixes: list[dict[str, Any]],
) -> None:
    """Phase one output: enough for the palette and schema tests, with no artwork behind it."""
    for remix in remixes:
        remix["_layerPaths"] = ["assets/artwork/pending.webp"] * len(remix["layers"])
        remix["_thumb"] = "assets/artwork/pending.webp"
    runtime = [runtime_remix(remix) for remix in remixes]
    catalog = {
        "designIds": [design["id"] for design in designs],
        "remixIds": [remix["id"] for remix in runtime],
        "designs": designs,
        "remixes": runtime,
    }
    out.mkdir(parents=True, exist_ok=True)
    _atomic_json(out / "catalog.json", catalog)
    print(f"Wrote {out / 'catalog.json'}")


def write_fixtures(
    out: Path,
    definitions: dict[str, Any],
    remixes: list[dict[str, Any]],
    size: int,
) -> None:
    """The reference frames `SceneRendererTest` compares its GL output against.

    Composited the same way the renderer will — layers alpha-blended in order — at the
    full frame, because the test crops by whatever `SceneCoverage` granted before it
    compares.
    """
    from PIL import Image

    by_id = {remix["id"]: remix for remix in remixes}
    designs_by_id = {design["id"]: design for design in definitions["design"]}
    out.mkdir(parents=True, exist_ok=True)
    for existing in (*out.glob("*.webp"), *out.glob("*.png")):
        existing.unlink()
    for remix_id in FIXTURE_REMIXES:
        remix = by_id[remix_id]
        design = designs_by_id[remix["designId"]]
        images = [
            render_layer_image(design, index, remix["palette"], size)
            for index in range(len(design["layer"]))
        ]
        if remix["night"]:
            images = [night_image(image) for image in images]
        frame = compose(images)
        flattened = Image.new("RGB", frame.size, (0, 0, 0))
        flattened.paste(frame, mask=frame.getchannel("A"))
        path = out / f"{remix_id}.webp"
        flattened.save(path, format="WEBP", quality=95, method=6)
        print(f"  fixture {remix_id} -> {path.stat().st_size / 1024:.0f} KiB")


def _atomic_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=1, sort_keys=True) + "\n")
    temporary.replace(path)


# --------------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------------


def render_variant(
    design: dict[str, Any],
    variant_index: int,
    size: int,
    artwork: Path,
) -> dict[str, Any]:
    """Render one variant's layers and both of its remixes' assets.

    The dark twin is derived from the very same layer renders, so the pair is the same
    artwork at two tones rather than two draws that happen to share a recipe.
    """
    variant = design["variant"][variant_index]
    images = [
        render_layer_image(design, index, variant["palette"], size)
        for index in range(len(design["layer"]))
    ]
    if variant_index == 0:
        check_animation_shows(design, images)
    result: dict[str, Any] = {"records": {}, "remixes": {}}
    for night in (False, True):
        posed = [night_image(image) for image in images] if night else images
        layer_paths = []
        for image in posed:
            data = encode_layer(image)
            asset_path, digest = store(artwork, data)
            layer_paths.append(asset_path)
            result["records"][digest] = {
                "assetPath": asset_path,
                "sha256": digest,
                "bytes": len(data),
            }
        thumb_data = encode_thumb(compose(posed))
        thumb_path, thumb_digest = store(artwork, thumb_data)
        result["records"][thumb_digest] = {
            "assetPath": thumb_path,
            "sha256": thumb_digest,
            "bytes": len(thumb_data),
        }
        remix_id = f"{design['id']}_hue_{variant['slug']}" + ("_dark" if night else "")
        result["remixes"][remix_id] = {
            "_layerPaths": layer_paths,
            "_thumb": thumb_path,
            "_isDark": bool(scene_tone(thumb_data) < DARK_TONE_CROSSOVER),
        }
    return result


def render_spinner_design(
    design_id: str,
    remix_specs: list[Any],
    remixes: list[dict[str, Any]],
    size: int,
    artwork: Path,
) -> dict[str, Any]:
    """Render one spinner design: its canonical layers once, then every variant's assets.

    Effect variants share canonical layer images by layer key, and content addressing
    carries that sharing into the shipped catalogue. Each design gets its own
    `RenderEngine` so its base-render cache stays thread-local. Thumbs are composed
    from the encoded layers, which is also what the GPU will sample.
    """
    from PIL import Image

    from artwork.renderers import RenderEngine

    engine = RenderEngine(seed=SEED)
    result: dict[str, Any] = {"records": {}, "remixes": {}}
    stored: dict[str, tuple[str, str]] = {}
    by_id = {spec.remix_id: spec for spec in remix_specs}
    for remix in remixes:
        spec = by_id[remix["id"]]
        for layer_spec in spec.layers:
            if layer_spec.source_url in stored:
                continue
            data = encode_layer(engine.render_layer(spec, layer_spec, size))
            asset_path, digest = store(artwork, data)
            stored[layer_spec.source_url] = (asset_path, digest)
            result["records"][digest] = {
                "assetPath": asset_path,
                "sha256": digest,
                "bytes": len(data),
            }

    checked = False
    for remix in remixes:
        spec = by_id[remix["id"]]
        images = [
            Image.open(artwork / f"{stored[layer.source_url][1]}.webp")
            for layer in spec.layers
        ]
        if not checked:
            check_animation_shows({"id": design_id, "layer": remix["layers"]}, images)
            checked = True
        thumb_data = encode_thumb(compose(images))
        for image in images:
            image.close()
        thumb_path, thumb_digest = store(artwork, thumb_data)
        result["records"][thumb_digest] = {
            "assetPath": thumb_path,
            "sha256": thumb_digest,
            "bytes": len(thumb_data),
        }
        result["remixes"][remix["id"]] = {
            "_layerPaths": [stored[layer.source_url][0] for layer in spec.layers],
            "_thumb": thumb_path,
            "_isDark": bool(scene_tone(thumb_data) < DARK_TONE_CROSSOVER),
        }
    return result


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    definitions = load_definitions(args.definitions)
    designs, remixes = build_catalog(definitions)
    layer_count = sum(len(remix["layers"]) for remix in remixes)
    print(f"{len(designs)} pieces, {len(remixes)} variants, {layer_count} layers")

    if args.metadata_only:
        write_metadata_only(args.out, designs, remixes)
        return 0

    if args.fixtures:
        write_fixtures(args.fixtures, definitions, remixes, args.size or FIXTURE_EDGE)
        return 0

    size = args.size or MASTER_EDGE
    artwork = args.out / "artwork"
    if artwork.exists():
        shutil.rmtree(artwork)
    artwork.mkdir(parents=True, exist_ok=True)

    jobs = [
        (lambda design=design, index=variant_index: render_variant(design, index, size, artwork))
        for design in definitions["design"]
        for variant_index in range(len(design["variant"]))
    ]
    specs_by_design: dict[str, list[Any]] = {}
    for spec in load_catalog(definitions["spinnerDesignsPath"]).remixes:
        specs_by_design.setdefault(spec.design_id, []).append(spec)
    spinner_remixes_by_design: dict[str, list[dict[str, Any]]] = {}
    for remix in remixes:
        if remix.get("spinnerDesign"):
            spinner_remixes_by_design.setdefault(remix["designId"], []).append(remix)
    jobs += [
        (
            lambda design_id=design_id, metas=metas: render_spinner_design(
                design_id,
                specs_by_design[design_id],
                metas,
                size,
                artwork,
            )
        )
        for design_id, metas in spinner_remixes_by_design.items()
    ]
    workers = args.workers or min(4, os.cpu_count() or 1)
    if workers == 1:
        results = [job() for job in jobs]
    else:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(lambda job: job(), jobs))

    records: dict[str, dict[str, Any]] = {}
    rendered: dict[str, dict[str, Any]] = {}
    for result in results:
        records.update(result["records"])
        rendered.update(result["remixes"])
    for remix in remixes:
        remix.update(rendered[remix["id"]])

    write_catalog(args.out, designs, remixes, records)
    total = sum(record["bytes"] for record in records.values())
    print(f"Wrote {len(records)} files, {total / 1_048_576:.1f} MiB")
    return 0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--definitions",
        type=Path,
        default=Path(__file__).parent / "catalog",
        help="Directory holding designs.toml",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).parent.parent / "app/src/main/assets",
        help="Asset root to write artwork/ and catalog/ into",
    )
    parser.add_argument(
        "--metadata-only",
        action="store_true",
        help="Emit catalog.json without rendering, so the palette tests can run first",
    )
    parser.add_argument("--size", type=int, help="Override the master edge, for quick previews")
    parser.add_argument(
        "--workers",
        type=int,
        help="Concurrent variant renders; the output is identical at any setting",
    )
    parser.add_argument(
        "--fixtures",
        type=Path,
        help="Write SceneRendererTest's reference composites here instead of the catalogue",
    )
    return parser.parse_args(argv)


def load_definitions(directory: Path) -> dict[str, Any]:
    path = directory / "designs.toml"
    spinner_designs = directory / "spinner_designs.json"
    for required in (path, spinner_designs):
        if not required.is_file():
            raise SystemExit(f"Missing {required}")
    definitions = tomllib.loads(path.read_text())
    definitions["spinnerDesigns"] = json.loads(spinner_designs.read_text())
    definitions["spinnerDesignsPath"] = spinner_designs
    return definitions


if __name__ == "__main__":
    raise SystemExit(main())
