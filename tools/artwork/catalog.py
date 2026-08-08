"""Catalog parsing and validation.

Only metadata is consumed here.  The renderer deliberately never opens the
pixel assets referenced by ``imageUrl`` or ``previews``.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True, slots=True)
class LayerSpec:
    index: int
    source_url: str
    kind: str
    parallax_scale: float
    rotation_direction: str | None
    rotation_time: int | None

    @property
    def suffix(self) -> str:
        return Path(self.source_url).suffix.lower()

    @property
    def source_key(self) -> str:
        return self.source_url.removeprefix("assets/")


@dataclass(frozen=True, slots=True)
class RemixSpec:
    design_id: str
    remix_id: str
    label: str
    layers: tuple[LayerSpec, ...]
    input_rotation_scaler: float

    @property
    def is_multilayered(self) -> bool:
        return len(self.layers) > 1


@dataclass(frozen=True, slots=True)
class Catalog:
    path: Path
    remixes: tuple[RemixSpec, ...]
    source_paths: tuple[Path, ...] = ()

    def __post_init__(self) -> None:
        if not self.source_paths:
            object.__setattr__(self, "source_paths", (self.path,))

    @property
    def design_ids(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(r.design_id for r in self.remixes))

    @property
    def unique_layer_urls(self) -> tuple[str, ...]:
        return tuple(
            dict.fromkeys(layer.source_url for remix in self.remixes for layer in remix.layers)
        )

    @property
    def logical_layer_count(self) -> int:
        return sum(len(remix.layers) for remix in self.remixes)

    def select(
        self,
        *,
        designs: Iterable[str] = (),
        remixes: Iterable[str] = (),
    ) -> tuple[RemixSpec, ...]:
        design_set = set(designs)
        remix_set = set(remixes)
        unknown_designs = design_set.difference(self.design_ids)
        known_remixes = {r.remix_id for r in self.remixes}
        unknown_remixes = remix_set.difference(known_remixes)
        if unknown_designs:
            raise ValueError(f"unknown design id(s): {', '.join(sorted(unknown_designs))}")
        if unknown_remixes:
            raise ValueError(f"unknown remix id(s): {', '.join(sorted(unknown_remixes))}")
        if not design_set and not remix_set:
            return self.remixes
        return tuple(
            remix
            for remix in self.remixes
            if remix.design_id in design_set or remix.remix_id in remix_set
        )


def _rotation(layer: dict[str, Any]) -> tuple[str | None, int | None]:
    rotation = layer.get("rotation") or {}
    direction = rotation.get("direction")
    time = rotation.get("time")
    return direction, int(time) if time is not None else None


def _catalog_documents(
    path: Path,
    *,
    ancestors: tuple[Path, ...] = (),
) -> list[tuple[Path, dict[str, Any]]]:
    resolved = path.resolve()
    if resolved in ancestors:
        cycle = " -> ".join(item.name for item in (*ancestors, resolved))
        raise ValueError(f"catalog include cycle: {cycle}")

    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"catalog root must be an object: {path}")
    includes = raw.get("includes", [])
    if not isinstance(includes, list) or not all(isinstance(item, str) for item in includes):
        raise ValueError(f"catalog includes must be a list of paths: {path}")

    documents = [(path, raw)]
    for include in includes:
        documents.extend(
            _catalog_documents(path.parent / include, ancestors=(*ancestors, resolved))
        )
    return documents


def load_catalog(path: str | Path) -> Catalog:
    catalog_path = Path(path)
    documents = _catalog_documents(catalog_path)
    parsed: list[RemixSpec] = []
    for source_path, raw in documents:
        remixes = raw.get("remixes", [])
        if not isinstance(remixes, list):
            raise ValueError(f"catalog remixes must be a list: {source_path}")
        for remix in remixes:
            layers: list[LayerSpec] = []
            for index, layer in enumerate(remix["layers"]):
                direction, time = _rotation(layer)
                layers.append(
                    LayerSpec(
                        index=index,
                        source_url=layer["imageUrl"],
                        kind=layer["type"],
                        parallax_scale=float(layer.get("parallaxScale", 0.0)),
                        rotation_direction=direction,
                        rotation_time=time,
                    )
                )
            if not layers:
                raise ValueError(f"remix has no layers: {remix['id']}")
            parsed.append(
                RemixSpec(
                    design_id=remix["designId"],
                    remix_id=remix["id"],
                    label=remix["label"],
                    layers=tuple(layers),
                    input_rotation_scaler=float(remix.get("inputRotationScaler", 1.0)),
                )
            )

    if not parsed:
        raise ValueError(f"catalog contains no remixes: {catalog_path}")
    remix_counts = Counter(remix.remix_id for remix in parsed)
    duplicate_ids = sorted(remix_id for remix_id, count in remix_counts.items() if count > 1)
    if duplicate_ids:
        raise ValueError(f"duplicate remix id(s): {', '.join(duplicate_ids)}")

    catalog = Catalog(
        path=catalog_path,
        source_paths=tuple(source_path for source_path, _ in documents),
        remixes=tuple(parsed),
    )
    return catalog
