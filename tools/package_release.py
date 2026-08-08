# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Verify and package Gyre's signed release APK with its SHA-256 checksum."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


MAX_APK_BYTES = 450 * 1024 * 1024
EXPECTED_PACKAGE = "dev.gyre.wallpaper"
EXPECTED_API = "37"
# One image per layer reference plus one thumb per remix, less the sharing among effect
# variants, whose layers deduplicate by content address.
EXPECTED_ARTWORK_FILES = 948
EXPECTED_CATALOG_FILES = {
    "assets/catalog/catalog.json",
    "assets/catalog/checksums.json",
}
EXPECTED_COMPONENTS = {
    "activity": {"dev.gyre.wallpaper.MainActivity"},
    "service": {"dev.gyre.wallpaper.wallpaper.GyreWallpaperService"},
    "receiver": set(),
    "provider": set(),
}


def find_sdk_tool(name: str) -> Path | None:
    direct = shutil.which(name)
    if direct:
        return Path(direct)
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        return None
    candidates = sorted(
        (Path(sdk_root) / "build-tools").glob(f"*/{name}"),
        reverse=True,
    )
    return candidates[0] if candidates else None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def command_output(command: list[str]) -> str:
    result = subprocess.run(
        command,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return result.stdout


def quoted_badging_value(badging: str, label: str) -> str:
    match = re.search(rf"^{re.escape(label)}:'([^']+)'", badging, re.MULTILINE)
    if match is None:
        raise ValueError(f"APK badging is missing {label}")
    return match.group(1)


def verify_identity_and_permissions(apk: Path, aapt2: Path) -> str:
    """Check identity, API level, and permissions; return the APK's version name."""
    badging = command_output([str(aapt2), "dump", "badging", str(apk)])
    package_match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if package_match is None or package_match.group(1) != EXPECTED_PACKAGE:
        found = package_match.group(1) if package_match else "missing"
        raise ValueError(f"unexpected package identity: {found}")
    version_match = re.search(r"^package:.*\bversionName='([^']+)'", badging, re.MULTILINE)
    if version_match is None:
        raise ValueError("APK badging is missing versionName")
    if quoted_badging_value(badging, "minSdkVersion") != EXPECTED_API:
        raise ValueError(f"minimum API must be {EXPECTED_API}")
    if quoted_badging_value(badging, "targetSdkVersion") != EXPECTED_API:
        raise ValueError(f"target API must be {EXPECTED_API}")
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.MULTILINE))
    if permissions:
        raise ValueError(f"release APK declares unwanted permissions: {sorted(permissions)}")
    return version_match.group(1)


def manifest_components(xml_tree: str) -> dict[str, set[str]]:
    components = {component: set() for component in EXPECTED_COMPONENTS}
    lines = xml_tree.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)E: (activity|service|receiver|provider)(?:\s|$)", line)
        if match is None:
            continue
        indentation = len(match.group(1))
        component = match.group(2)
        for detail in lines[index + 1 :]:
            detail_indentation = len(detail) - len(detail.lstrip())
            if detail_indentation <= indentation and detail.lstrip().startswith("E: "):
                break
            name = re.search(r'A: .*:name\([^)]*\)="([^"]+)"', detail)
            if name:
                components[component].add(name.group(1))
                break
    return components


def verify_components(apk: Path, aapt2: Path) -> None:
    tree = command_output(
        [str(aapt2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"],
    )
    components = manifest_components(tree)
    if components != EXPECTED_COMPONENTS:
        raise ValueError(f"unexpected Android components: {components}")


def verify_bundled_assets(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        missing_catalog = EXPECTED_CATALOG_FILES - names
        if missing_catalog:
            raise ValueError(f"release APK is missing catalogue files: {sorted(missing_catalog)}")
        artwork = {
            name
            for name in names
            if name.startswith("assets/artwork/") and not name.endswith("/")
        }
        if len(artwork) != EXPECTED_ARTWORK_FILES:
            raise ValueError(
                f"release APK has {len(artwork)} artwork files; "
                f"expected {EXPECTED_ARTWORK_FILES}",
            )
        # Every remix must still reach a thumb, since the app has no larger preview to fall back on.
        thumbless = [
            remix["id"]
            for remix in json.loads(archive.read("assets/catalog/catalog.json"))["remixes"]
            if not remix.get("previews", {}).get("thumb")
        ]
        if thumbless:
            raise ValueError(f"remixes ship without a thumb: {thumbless[:5]}")
        catalog = json.loads(archive.read("assets/catalog/catalog.json"))
        checksums = json.loads(archive.read("assets/catalog/checksums.json"))
        counts = checksums.get("counts", {})
        expected_counts = {
            "designs": 26,
            "remixes": 352,
            "layers": 728,
            "uniqueAssetFiles": EXPECTED_ARTWORK_FILES,
        }
        if counts != expected_counts:
            raise ValueError(f"unexpected catalogue checksum counts: {counts}")
        if len(catalog.get("designs", [])) != expected_counts["designs"]:
            raise ValueError("normalized catalogue design count does not match")
        if len(catalog.get("remixes", [])) != expected_counts["remixes"]:
            raise ValueError("normalized catalogue remix count does not match")
        layer_count = sum(len(remix.get("layers", [])) for remix in catalog["remixes"])
        if layer_count != expected_counts["layers"]:
            raise ValueError("normalized catalogue layer count does not match")
        manifest_assets = checksums.get("assets", [])
        # One entry per file the generator wrote. Far fewer than the layer count, because a
        # piece's colour variants share their masks and differ only by the ramp in the catalogue.
        manifest_paths = {entry["assetPath"] for entry in manifest_assets}
        if len(manifest_paths) != expected_counts["uniqueAssetFiles"]:
            raise ValueError("checksum manifest does not cover every shipped asset")
        unlisted = artwork - manifest_paths
        if unlisted:
            raise ValueError(f"APK ships artwork the manifest does not list: {sorted(unlisted)[:5]}")
        covered_artwork = {entry.get("assetPath") for entry in manifest_assets}
        if covered_artwork != artwork:
            raise ValueError("checksum manifest does not cover every artwork file")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apk",
        type=Path,
        default=Path("app/build/outputs/apk/release/app-release.apk"),
    )
    parser.add_argument("--output-dir", type=Path, default=Path("dist"))
    parser.add_argument(
        "--version",
        help="artifact version label; defaults to the APK's own versionName",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.apk.is_file():
        print(f"error: release APK not found: {args.apk}", file=sys.stderr)
        return 1
    size = args.apk.stat().st_size
    if size > MAX_APK_BYTES:
        print(
            f"error: APK is {size / 1024 / 1024:.1f} MiB; limit is 450 MiB",
            file=sys.stderr,
        )
        return 1
    apksigner = find_sdk_tool("apksigner")
    aapt2 = find_sdk_tool("aapt2")
    if apksigner is None or aapt2 is None:
        print(
            "error: Android build tools not found; set ANDROID_HOME or put "
            "build-tools on PATH (see README.md)",
            file=sys.stderr,
        )
        return 1
    try:
        version = verify_identity_and_permissions(args.apk, aapt2)
        if args.version:
            version = args.version
        verify_components(args.apk, aapt2)
        verify_bundled_assets(args.apk)
        subprocess.run(
            [str(apksigner), "verify", "--verbose", "--print-certs", str(args.apk)],
            check=True,
        )
        args.output_dir.mkdir(parents=True, exist_ok=True)
        output = args.output_dir / f"Gyre-{version}-release.apk"
        shutil.copy2(args.apk, output)
        checksum = sha256_file(output)
        (args.output_dir / "SHA256SUMS").write_text(
            f"{checksum}  {output.name}\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"Packaged {output} ({size / 1024 / 1024:.1f} MiB)")
    print(f"SHA-256: {checksum}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
