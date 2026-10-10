"""
Area-based cache for OpenStreetMap downloads.

Each download is stored together with the bounding box it covers. A later
request whose bounding box lies inside a cached area is served from it
(clipped to the requested box), so posters anywhere inside a previously
downloaded or prefetched area need no network access. On a miss, a padded,
grid-snapped area is downloaded so that nearby future requests hit as well.
"""

import hashlib
import json
import math
import os
import pickle
import uuid
from pathlib import Path
from typing import Any, Callable

import osmnx as ox

# (west, south, east, north) in degrees, the order osmnx 2.x uses
Bbox = tuple[float, float, float, float]

INDEX_FILE = "regions.json"
# On a miss, grow the requested box on every side by this much...
MISS_PADDING_RATIO = 0.25
MISS_MIN_PADDING_M = 2000
# ...then snap it outward to grid lines (0.01° ≈ 1.1 km).
GRID_DEG = 0.01
_EPS = 1e-9
_M_PER_DEG_LAT = 111_320.0


def request_bbox(point: tuple[float, float], dist: float) -> Bbox:
    """Bounding box of a square of half-width `dist` meters around (lat, lon)."""
    west, south, east, north = ox.utils_geo.bbox_from_point(point, dist)
    return (float(west), float(south), float(east), float(north))


def snap_bbox(bbox: Bbox) -> Bbox:
    """Grow a box outward to the nearest grid lines."""
    west, south, east, north = bbox
    return (
        round(math.floor(west / GRID_DEG) * GRID_DEG, 6),
        round(max(-90.0, math.floor(south / GRID_DEG) * GRID_DEG), 6),
        round(math.ceil(east / GRID_DEG) * GRID_DEG, 6),
        round(min(90.0, math.ceil(north / GRID_DEG) * GRID_DEG), 6),
    )


def padded_bbox(bbox: Bbox) -> Bbox:
    """Area to download on a cache miss: the request plus padding, snapped to the grid."""
    west, south, east, north = bbox
    m_per_deg_lon = _M_PER_DEG_LAT * max(0.01, math.cos(math.radians((south + north) / 2)))
    pad_lat = max(MISS_MIN_PADDING_M / _M_PER_DEG_LAT, (north - south) * MISS_PADDING_RATIO)
    pad_lon = max(MISS_MIN_PADDING_M / m_per_deg_lon, (east - west) * MISS_PADDING_RATIO)
    return snap_bbox((west - pad_lon, south - pad_lat, east + pad_lon, north + pad_lat))


def contains(outer: Bbox, inner: Bbox) -> bool:
    return (outer[0] <= inner[0] + _EPS and outer[1] <= inner[1] + _EPS
            and outer[2] >= inner[2] - _EPS and outer[3] >= inner[3] - _EPS)


def _area(bbox: Bbox) -> float:
    return (bbox[2] - bbox[0]) * (bbox[3] - bbox[1])


def layer_name(name: str, tags: dict | None = None) -> str:
    """Cache layer id; tagged layers include a hash of the tags so changed tags never reuse stale data."""
    if tags is None:
        return name
    digest = hashlib.sha1(json.dumps(tags, sort_keys=True).encode()).hexdigest()[:10]
    return f"{name}-{digest}"


class RegionCache:
    """Pickled downloads in `cache_dir`, indexed by layer and covered bounding box."""

    def __init__(self, cache_dir: str | Path):
        self.dir = Path(cache_dir)
        self.index_path = self.dir / INDEX_FILE

    def get(
        self,
        layer: str,
        bbox: Bbox,
        download: Callable[[Bbox], Any],
        clip: Callable[[Any, Bbox], Any],
    ) -> tuple[Any, bool]:
        """Return (data clipped to `bbox`, was_cached), downloading a padded area on a miss."""
        cached = self._find(layer, bbox)
        if cached is not None:
            return clip(cached, bbox), True
        area = padded_bbox(bbox)
        data = download(area)
        self._store(layer, area, data)
        return clip(data, bbox), False

    def ensure(self, layer: str, bbox: Bbox, download: Callable[[Bbox], Any]) -> bool:
        """Make sure `bbox` is cached (prefetch). Returns True if a download was needed."""
        if self._covered(layer, bbox):
            return False
        area = snap_bbox(bbox)
        self._store(layer, area, download(area))
        return True

    # --- index ------------------------------------------------------------

    def _load_index(self) -> list[dict]:
        try:
            return json.loads(self.index_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return []
        except (OSError, ValueError) as exc:
            print(f"⚠ Ignoring unreadable cache index {self.index_path}: {exc}")
            return []

    def _save_index(self, entries: list[dict]) -> None:
        tmp = self.index_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(entries, indent=1), encoding="utf-8")
        os.replace(tmp, self.index_path)

    def _candidates(self, layer: str, bbox: Bbox) -> list[dict]:
        return [
            e for e in self._load_index()
            if e["layer"] == layer and contains(tuple(e["bbox"]), bbox)
            and (self.dir / e["file"]).exists()
        ]

    def _covered(self, layer: str, bbox: Bbox) -> bool:
        return bool(self._candidates(layer, bbox))

    def _find(self, layer: str, bbox: Bbox) -> Any | None:
        candidates = self._candidates(layer, bbox)
        if not candidates:
            return None
        # The smallest covering area is the quickest to load and clip.
        best = min(candidates, key=lambda e: _area(tuple(e["bbox"])))
        try:
            with open(self.dir / best["file"], "rb") as f:
                return pickle.load(f)
        except (OSError, pickle.UnpicklingError, EOFError) as exc:
            print(f"⚠ Ignoring unreadable cache file {best['file']}: {exc}")
            return None

    def _store(self, layer: str, bbox: Bbox, data: Any) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        file = f"region_{layer}_{uuid.uuid4().hex[:12]}.pkl"
        tmp = self.dir / (file + ".tmp")
        with open(tmp, "wb") as f:
            pickle.dump(data, f, protocol=pickle.HIGHEST_PROTOCOL)
        os.replace(tmp, self.dir / file)

        entries = []
        for entry in self._load_index():
            # Areas fully inside the new one are redundant now.
            if entry["layer"] == layer and contains(bbox, tuple(entry["bbox"])):
                (self.dir / entry["file"]).unlink(missing_ok=True)
                continue
            entries.append(entry)
        entries.append({"layer": layer, "bbox": list(bbox), "file": file})
        self._save_index(entries)
