# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Package a Gyre asset tree as a pack the app can import.

Gyre bundles only artwork it generates itself. A catalogue it cannot ship — anything this
repository does not license, or any asset tree laid out the same way — becomes a pack
instead: one zip holding a manifest, the catalogue and the artwork, side-loaded onto the
phone and imported through the app's collection sheet.

    uv run tools/export_pack.py --assets /path/to/another/asset/tree \\
        --name "A Collection" --out dist/a-collection.zip
    uv run tools/export_pack.py --verify dist/a-collection.zip

Nothing here decodes an image: dimensions are read from the file headers, so the script needs
no Pillow and runs wherever a checkout does.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


FORMAT_VERSION = 1

# The renderer multiplies the user's spin by each remix's scaler, and the spin wraps over this
# many whole turns (MotionMath.SPIN_WRAP_TURNS). Their product has to be a whole number of turns
# or a flick never returns the artwork to where it started.
SPIN_WRAP_TURNS = 4

# The largest edge SceneRenderer will upload without an inSampleSize halving on a phone-sized
# viewport. Anything above this risks GL_MAX_TEXTURE_SIZE on a 4096 device.
MAX_EDGE = 4096

ASSET_PREFIX = "assets/artwork/"
CONTENT_ADDRESSED = re.compile(r"^([0-9a-f]{64})\.(webp|png|jpg|jpeg)$")
LFS_POINTER = b"version https://git-lfs.github.com/spec/v1"


class PackError(Exception):
    """A refusal, reported without a traceback."""


# --------------------------------------------------------------------------- image headers


def image_size(data: bytes, label: str) -> tuple[int, int]:
    """Width and height from [data]'s header alone."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        if data[12:16] != b"IHDR":
            raise PackError(f"{label}: PNG does not start with IHDR")
        return struct.unpack(">II", data[16:24])
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return webp_size(data, label)
    if data[:2] == b"\xff\xd8":
        return jpeg_size(data, label)
    if data.startswith(LFS_POINTER):
        raise PackError(
            f"{label} is a Git LFS pointer, not an image. "
            "Run `git lfs pull` in the source checkout first.",
        )
    raise PackError(f"{label}: unrecognised image format")


def webp_size(data: bytes, label: str) -> tuple[int, int]:
    chunk = data[12:16]
    if chunk == b"VP8 ":
        if data[23:26] != b"\x9d\x01\x2a":
            raise PackError(f"{label}: lossy WebP has no keyframe sync code")
        width, height = struct.unpack("<HH", data[26:30])
        return width & 0x3FFF, height & 0x3FFF
    if chunk == b"VP8L":
        if data[20] != 0x2F:
            raise PackError(f"{label}: lossless WebP has no signature byte")
        bits = int.from_bytes(data[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if chunk == b"VP8X":
        width = int.from_bytes(data[24:27], "little") + 1
        height = int.from_bytes(data[27:30], "little") + 1
        return width, height
    raise PackError(f"{label}: unrecognised WebP chunk {chunk!r}")


def jpeg_size(data: bytes, label: str) -> tuple[int, int]:
    offset = 2
    while offset + 9 < len(data):
        if data[offset] != 0xFF:
            offset += 1
            continue
        marker = data[offset + 1]
        # Start-of-frame, in every flavour but the four that carry tables instead.
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height, width = struct.unpack(">HH", data[offset + 5 : offset + 9])
            return width, height
        offset += 2 + struct.unpack(">H", data[offset + 2 : offset + 4])[0]
    raise PackError(f"{label}: JPEG has no start-of-frame marker")


# --------------------------------------------------------------------------- validation


def catalogue_paths(catalogue: dict) -> set[str]:
    """Every artwork path the catalogue names, layers and thumbs alike."""
    paths: set[str] = set()
    for remix in catalogue["remixes"]:
        paths.update(layer["imageUrl"] for layer in remix["layers"])
        paths.add(remix["previews"]["thumb"])
    return paths


def validate_catalogue(catalogue: dict, blob: str) -> None:
    """Everything checkable without touching a pixel, reported all at once."""
    problems: list[str] = []

    if "http" in blob:
        remote = sorted(
            {
                path
                for path in catalogue_paths(catalogue)
                if path.startswith(("http://", "https://"))
            },
        )
        problems.append(f"remote artwork paths: {remote[:5]}")

    remixes = {remix["id"]: remix for remix in catalogue["remixes"]}
    for design in catalogue["designs"]:
        own = design.get("remixIds") or []
        if not own:
            problems.append(f"design {design['id']} has no remixes")
        missing = [id for id in own if id not in remixes]
        if missing:
            problems.append(f"design {design['id']} names unknown remixes: {missing[:3]}")
        if design.get("previewRemixId") not in remixes:
            problems.append(f"design {design['id']} has no resolvable preview")

    for remix in catalogue["remixes"]:
        if not remix.get("layers"):
            problems.append(f"remix {remix['id']} has no layers")
        if not remix.get("previews", {}).get("thumb"):
            problems.append(f"remix {remix['id']} has no thumb")
        turns = remix.get("inputRotationScaler", 1.0) * SPIN_WRAP_TURNS
        if abs(turns - round(turns)) > 1e-4:
            problems.append(
                f"remix {remix['id']} scaler {remix['inputRotationScaler']} "
                f"does not wrap over {SPIN_WRAP_TURNS} turns",
            )

    for path in sorted(catalogue_paths(catalogue)):
        if not path.startswith(ASSET_PREFIX):
            problems.append(f"artwork path outside {ASSET_PREFIX}: {path}")
        elif not CONTENT_ADDRESSED.match(path.removeprefix(ASSET_PREFIX)):
            problems.append(f"artwork path is not content-addressed: {path}")

    if problems:
        raise PackError("the catalogue cannot be packed:\n  " + "\n  ".join(problems))


def validate_geometry(catalogue: dict, sizes: dict[str, tuple[int, int]]) -> list[str]:
    """
    Refuses artwork the renderer would draw wrongly, and reports what it merely tolerated.

    A rotating base layer must be square. The shader turns the scene in normalised coordinates,
    so on a non-square image that rotation is a shear rather than a rotation — the artwork
    stretches as it turns, and no framing rule can undo it. Layers above the base sit on
    transparent surrounds and are exempt.
    """
    problems: list[str] = []
    notes: list[str] = []
    for remix in catalogue["remixes"]:
        base = remix["layers"][0]
        width, height = sizes[base["imageUrl"]]
        if base.get("rotation") is not None and width != height:
            problems.append(f"remix {remix['id']} rotates a {width}x{height} base layer")
        if base.get("rotation") is None and base.get("parallaxScale", 0) > 0:
            notes.append(remix["id"])

    if problems:
        raise PackError("the artwork cannot be packed:\n  " + "\n  ".join(problems))
    if notes:
        # SceneCoverage frames a static base by cover-fit and reserves no headroom for it, so
        # the importer clears the parallax rather than letting the pan reach past the edge.
        print(
            f"note: {len(notes)} remixes pan a static base layer; "
            "the app will import them without parallax",
        )
    return notes


# --------------------------------------------------------------------------- packing


def collect(assets: Path) -> tuple[dict, str, list[dict], list[str]]:
    catalogue_file = assets / "catalog" / "catalog.json"
    if not catalogue_file.is_file():
        raise PackError(f"no catalogue at {catalogue_file}")
    blob = catalogue_file.read_text(encoding="utf-8")
    catalogue = json.loads(blob)
    validate_catalogue(catalogue, blob)

    entries: list[dict] = []
    sizes: dict[str, tuple[int, int]] = {}
    for path in sorted(catalogue_paths(catalogue)):
        name = path.removeprefix(ASSET_PREFIX)
        source = assets / "artwork" / name
        if not source.is_file():
            raise PackError(f"{path} is named by the catalogue but not present")
        data = source.read_bytes()
        # Checked before the digest, because an unpulled LFS tree fails every other test too and
        # "does not hash to its own name" is an alarming way to say "run git lfs pull".
        if data.startswith(LFS_POINTER):
            raise PackError(
                f"{path} is a Git LFS pointer, not an image. "
                "Run `git lfs pull` in the source checkout first.",
            )
        digest = hashlib.sha256(data).hexdigest()
        if digest != CONTENT_ADDRESSED.match(name).group(1):
            raise PackError(
                f"{path} does not hash to its own name "
                f"(found {digest[:16]}…); the source tree is corrupt or LFS is not pulled",
            )
        width, height = image_size(data, path)
        if max(width, height) > MAX_EDGE:
            raise PackError(f"{path} is {width}x{height}; the renderer's limit is {MAX_EDGE}")
        sizes[path] = (width, height)
        entries.append(
            {
                "path": f"artwork/{name}",
                "sha256": digest,
                "bytes": len(data),
                "width": width,
                "height": height,
            },
        )

    unparallaxed = validate_geometry(catalogue, sizes)
    return catalogue, blob, entries, unparallaxed


def manifest_for(
    catalogue: dict,
    blob: str,
    entries: list[dict],
    name: str,
    created: str,
) -> dict:
    # Content-derived, so the same tree always exports the same pack and the app can recognise
    # one it already holds instead of copying it a second time.
    digest = hashlib.sha256()
    for entry in entries:
        digest.update(entry["sha256"].encode())
    digest.update(blob.encode("utf-8"))
    return {
        "formatVersion": FORMAT_VERSION,
        "kind": "pack",
        "name": name,
        "packId": digest.hexdigest()[:16],
        "createdAt": created,
        "generator": f"tools/export_pack.py {FORMAT_VERSION}",
        "spinWrapTurns": SPIN_WRAP_TURNS,
        "counts": {
            "designs": len(catalogue["designs"]),
            "remixes": len(catalogue["remixes"]),
            "layers": sum(len(remix["layers"]) for remix in catalogue["remixes"]),
            "assets": len(entries),
        },
        "totalBytes": sum(entry["bytes"] for entry in entries),
        "assets": entries,
    }


def write_pack(out: Path, assets: Path, manifest: dict, blob: str, entries: list[dict]) -> None:
    """
    Writes the zip, manifest first.

    The app streams the archive rather than opening it randomly — a content Uri has no file to
    seek in, and staging 180 MiB to reach the central directory would double the disk it needs.
    So it must meet the manifest before anything it is meant to describe.
    """
    out.parent.mkdir(parents=True, exist_ok=True)
    temporary = out.with_suffix(out.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w") as archive:
        archive.writestr(
            zipfile.ZipInfo("gyre-pack.json"),
            json.dumps(manifest, indent=1, ensure_ascii=False, sort_keys=True),
            zipfile.ZIP_DEFLATED,
        )
        archive.writestr(zipfile.ZipInfo("catalog/catalog.json"), blob, zipfile.ZIP_DEFLATED)
        for entry in entries:
            name = entry["path"].removeprefix("artwork/")
            # Stored, not deflated: WebP and PNG are already compressed, so deflate spends the
            # time and gives back nothing.
            archive.write(assets / "artwork" / name, entry["path"], zipfile.ZIP_STORED)
    temporary.replace(out)


def verify(pack: Path) -> int:
    """Re-reads a pack the way the app will, in the order the app will."""
    with zipfile.ZipFile(pack) as archive:
        names = archive.namelist()
        if not names or names[0] != "gyre-pack.json":
            raise PackError("gyre-pack.json is not the first entry; the app streams and would miss it")
        manifest = json.loads(archive.read("gyre-pack.json"))
        if manifest.get("formatVersion") != FORMAT_VERSION:
            raise PackError(f"unknown pack format {manifest.get('formatVersion')}")
        catalogue = json.loads(archive.read("catalog/catalog.json"))
        validate_catalogue(catalogue, archive.read("catalog/catalog.json").decode("utf-8"))

        declared = {entry["path"]: entry for entry in manifest["assets"]}
        present = set(names) - {"gyre-pack.json", "catalog/catalog.json"}
        if present != set(declared):
            missing = sorted(set(declared) - present)[:5]
            extra = sorted(present - set(declared))[:5]
            raise PackError(f"manifest and archive disagree; missing {missing}, extra {extra}")

        for path, entry in declared.items():
            data = archive.read(path)
            if len(data) != entry["bytes"]:
                raise PackError(f"{path} is {len(data)} bytes, manifest says {entry['bytes']}")
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise PackError(f"{path} does not match its declared digest")

        counts = manifest["counts"]
        actual = {
            "designs": len(catalogue["designs"]),
            "remixes": len(catalogue["remixes"]),
            "layers": sum(len(remix["layers"]) for remix in catalogue["remixes"]),
            "assets": len(declared),
        }
        if counts != actual:
            raise PackError(f"manifest counts {counts} but the catalogue holds {actual}")

    print(
        f"{pack} verified: {manifest['name']} ({manifest['packId']}), "
        f"{counts['designs']} designs, {counts['remixes']} remixes, "
        f"{counts['assets']} files, {manifest['totalBytes'] / 1024 / 1024:.1f} MiB",
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--assets", type=Path, help="an assets/ directory holding catalog/ and artwork/")
    parser.add_argument("--name", default="Imported artwork", help="what the app calls this pack")
    parser.add_argument("--out", type=Path, help="the .zip to write")
    parser.add_argument("--verify", type=Path, help="re-read an existing pack and check it")
    parser.add_argument("--created", help="ISO timestamp to stamp, instead of now")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.verify:
        return verify(args.verify)
    if not args.assets or not args.out:
        raise PackError("--assets and --out are both required unless --verify is given")

    catalogue, blob, entries, _ = collect(args.assets)
    created = args.created or datetime.now(timezone.utc).isoformat(timespec="seconds")
    manifest = manifest_for(catalogue, blob, entries, args.name, created)
    write_pack(args.out, args.assets, manifest, blob, entries)
    print(
        f"wrote {args.out} ({args.out.stat().st_size / 1024 / 1024:.1f} MiB): "
        f"{manifest['counts']['designs']} designs, {manifest['counts']['remixes']} remixes, "
        f"{manifest['counts']['assets']} files",
    )
    return verify(args.out)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PackError as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
