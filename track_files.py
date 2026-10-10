"""
Track file parsing for the GPX map poster generator.

Reads GPS tracks from GPX, TCX and FIT files, optionally gzip-compressed
(e.g. activity.fit.gz as found in a Strava data export) or zipped (Garmin
Connect "Export Original"), and returns them as (lat, lon) tuples.
"""

import gzip
import io
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import BinaryIO

import fitdecode

# Guard against decompression bombs: real activity files are a few MB at most.
MAX_DECOMPRESSED_BYTES = 200 * 1024 * 1024
SUPPORTED_EXTENSIONS = (".gpx", ".tcx", ".fit")

_GZIP_MAGIC = b"\x1f\x8b"
_ZIP_MAGIC = b"PK\x03\x04"


def _validate_points(points: list[tuple[float, float]],
                     kind: str) -> list[tuple[float, float]]:
    if len(points) < 2:
        raise ValueError(
            f"{kind} file must contain at least 2 valid GPS coordinates")
    return points


def parse_gpx_points(
        gpx_file: str | BinaryIO) -> list[tuple[float, float]]:
    """Parse track/route points from a GPX file as (lat, lon) tuples."""
    try:
        tree = ET.parse(gpx_file)
        root = tree.getroot()
    except ET.ParseError as exc:
        raise ValueError(f"Invalid GPX file: {exc}") from exc

    points: list[tuple[float, float]] = []

    # Prefer track points; fall back to route points.
    trkpts = root.findall(".//{*}trkpt")
    rtepts = root.findall(".//{*}rtept") if not trkpts else []
    source = trkpts if trkpts else rtepts

    for node in source:
        lat = node.attrib.get("lat")
        lon = node.attrib.get("lon")
        if lat is None or lon is None:
            continue
        try:
            points.append((float(lat), float(lon)))
        except ValueError:
            continue

    if len(points) < 2:
        raise ValueError(
            "GPX file must contain at least 2 valid trkpt/rtept coordinates")

    return points


def parse_tcx_points(
        tcx_file: str | BinaryIO) -> list[tuple[float, float]]:
    """Parse trackpoint positions from a TCX file as (lat, lon) tuples."""
    try:
        root = ET.parse(tcx_file).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"Invalid TCX file: {exc}") from exc

    points: list[tuple[float, float]] = []
    for position in root.iterfind(".//{*}Trackpoint/{*}Position"):
        lat = position.findtext("{*}LatitudeDegrees")
        lon = position.findtext("{*}LongitudeDegrees")
        if lat is None or lon is None:
            continue
        try:
            points.append((float(lat), float(lon)))
        except ValueError:
            continue
    return _validate_points(points, "TCX")


def parse_fit_points(fit_file: str | BinaryIO) -> list[tuple[float, float]]:
    """Parse record positions from a FIT file as (lat, lon) tuples."""
    points: list[tuple[float, float]] = []
    try:
        with fitdecode.FitReader(
                fit_file,
                processor=fitdecode.StandardUnitsDataProcessor()) as reader:
            for frame in reader:
                if (not isinstance(frame, fitdecode.FitDataMessage)
                        or frame.name != "record"):
                    continue
                lat = frame.get_value("position_lat", fallback=None)
                lon = frame.get_value("position_long", fallback=None)
                if lat is None or lon is None:
                    continue
                points.append((float(lat), float(lon)))
    except fitdecode.FitError as exc:
        raise ValueError(f"Invalid FIT file: {exc}") from exc
    return _validate_points(points, "FIT")


def _gunzip(data: bytes) -> bytes:
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as gz:
        try:
            out = gz.read(MAX_DECOMPRESSED_BYTES + 1)
        except (OSError, EOFError) as exc:
            raise ValueError(f"Invalid gzip file: {exc}") from exc
    if len(out) > MAX_DECOMPRESSED_BYTES:
        raise ValueError("Decompressed track file is too large")
    return out


def _is_track_name(filename: str) -> bool:
    """True for *.gpx/.tcx/.fit, optionally with a trailing .gz."""
    path = Path(filename)
    if path.suffix.lower() == ".gz":
        path = path.with_suffix("")
    return path.suffix.lower() in SUPPORTED_EXTENSIONS


def _unzip_track(data: bytes) -> tuple[bytes, str]:
    """Extract the first track file from a zip (e.g. Garmin "Export Original")."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            for info in zf.infolist():
                if info.is_dir() or not _is_track_name(info.filename):
                    continue
                if info.file_size > MAX_DECOMPRESSED_BYTES:
                    raise ValueError("Track file inside zip is too large")
                with zf.open(info) as member:
                    return member.read(MAX_DECOMPRESSED_BYTES), info.filename
    except zipfile.BadZipFile as exc:
        raise ValueError(f"Invalid zip file: {exc}") from exc
    raise ValueError("Zip file contains no GPX, TCX or FIT file")


def _detect_format(data: bytes, extension: str) -> str:
    if extension in SUPPORTED_EXTENSIONS:
        return extension
    # Unknown extension: sniff the content.
    if len(data) >= 12 and data[8:12] == b".FIT":
        return ".fit"
    head = data[:4096].lstrip()
    if b"<TrainingCenterDatabase" in head:
        return ".tcx"
    if b"<gpx" in head:
        return ".gpx"
    raise ValueError(
        "Unsupported track file: expected GPX, TCX or FIT (optionally .gz)")


def track_stem(filename: str) -> str:
    """Filename without directory, .gz/.zip and track extension ("a/run.fit.gz" -> "run")."""
    path = Path(filename)
    if path.suffix.lower() in (".gz", ".zip"):
        path = path.with_suffix("")
    if path.suffix.lower() in SUPPORTED_EXTENSIONS:
        path = path.with_suffix("")
    return path.name


def parse_track_bytes(data: bytes,
                      filename: str = "") -> list[tuple[float, float]]:
    """Parse a GPX/TCX/FIT track (optionally gzip-compressed or zipped) from raw bytes."""
    if data[:4] == _ZIP_MAGIC:
        data, filename = _unzip_track(data)
    path = Path(filename)
    if data[:2] == _GZIP_MAGIC:
        data = _gunzip(data)
    if path.suffix.lower() == ".gz":
        path = path.with_suffix("")

    fmt = _detect_format(data, path.suffix.lower())
    if fmt == ".fit":
        return parse_fit_points(io.BytesIO(data))
    if fmt == ".tcx":
        return parse_tcx_points(io.BytesIO(data))
    return parse_gpx_points(io.BytesIO(data))


def parse_track_file(path: str | Path) -> list[tuple[float, float]]:
    """Parse a GPX/TCX/FIT track file (optionally .gz) from disk."""
    path = Path(path)
    return parse_track_bytes(path.read_bytes(), path.name)
