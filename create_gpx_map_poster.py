#!/usr/bin/env python3
"""
GPX Map Poster Generator

This script generates a map poster centered on a GPX track and overlays the GPX route
using a high-contrast color.
"""

import argparse
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import matplotlib.pyplot as plt
import osmnx as ox
from geopandas import GeoDataFrame, GeoSeries
from geopy.geocoders import Nominatim
from matplotlib.font_manager import FontProperties
from shapely.geometry import Point
from tqdm import tqdm

import create_map_poster as cmp


def parse_gpx_points(gpx_file: str) -> list[tuple[float, float]]:
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


def compute_center(points: list[tuple[float, float]]) -> tuple[float, float]:
    """Compute center from GPX bounding box midpoint."""
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    return ((min(lats) + max(lats)) / 2.0, (min(lons) + max(lons)) / 2.0)


def haversine_meters(lat1: float, lon1: float, lat2: float,
                     lon2: float) -> float:
    """Great-circle distance in meters between two WGS84 points."""
    r = 6371008.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = math.sin(dphi / 2)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(
        dlambda / 2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return r * c


def estimate_distance(points: list[tuple[float, float]],
                      center: tuple[float, float]) -> int:
    """Estimate map radius (meters) from farthest route point with margin."""
    c_lat, c_lon = center
    max_radius = max(
        haversine_meters(c_lat, c_lon, lat, lon) for lat, lon in points)
    return max(1200, int(max_radius * 1.10))


def get_compensated_dist(dist: int, width: float, height: float) -> float:
    """Scale fetch distance so cropped view still includes requested route radius."""
    return dist * (max(height, width) / min(height, width))


def expand_limits_to_include_route(
    xlim: tuple[float, float],
    ylim: tuple[float, float],
    xs: list[float],
    ys: list[float],
    pad_ratio: float = 0.05,
) -> tuple[tuple[float, float], tuple[float, float]]:
    """Expand viewport limits to guarantee all route points are visible."""
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)

    x0 = min(xlim[0], min_x)
    x1 = max(xlim[1], max_x)
    y0 = min(ylim[0], min_y)
    y1 = max(ylim[1], max_y)

    dx = x1 - x0
    dy = y1 - y0
    pad_x = max(50.0, dx * pad_ratio)
    pad_y = max(50.0, dy * pad_ratio)

    return ((x0 - pad_x, x1 + pad_x), (y0 - pad_y, y1 + pad_y))


def _hex_to_rgb(color: str) -> tuple[float, float, float]:
    color = color.lstrip("#")
    if len(color) != 6:
        return (1.0, 1.0, 1.0)
    r = int(color[0:2], 16) / 255.0
    g = int(color[2:4], 16) / 255.0
    b = int(color[4:6], 16) / 255.0
    return (r, g, b)


def _relative_luminance(color: str) -> float:
    r, g, b = _hex_to_rgb(color)

    def channel(c: float) -> float:
        if c <= 0.03928:
            return c / 12.92
        return ((c + 0.055) / 1.055)**2.4

    r_l = channel(r)
    g_l = channel(g)
    b_l = channel(b)
    return 0.2126 * r_l + 0.7152 * g_l + 0.0722 * b_l


def _contrast_ratio(color_a: str, color_b: str) -> float:
    l1 = _relative_luminance(color_a)
    l2 = _relative_luminance(color_b)
    lighter = max(l1, l2)
    darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)


def pick_route_color(theme: dict[str, str]) -> str:
    """Pick a route color that contrasts with map background and is aesthetically harmonious.
    
    Prefers colors based on theme warmth:
    - Warm themes (terracotta, sunset, warm_beige): prefer warm/saturated colors
    - Cool themes (midnight_blue, ocean, blueprint): prefer cool/bright colors
    """
    # Determine theme warmth from background color
    bg_color = theme["bg"]
    r, g, b = _hex_to_rgb(bg_color)
    # Warm if red channel is stronger than blue (even for light colors)
    is_warm_theme = r > b

    # Prefer warm colors for warm themes, cool colors for cool themes
    warm_candidates = [
        "#FF2D55",  # Rose/Pink
        "#FF6B35",  # Orange
        "#FFD400",  # Yellow
        "#FFFFFF",  # White (fallback)
    ]

    cool_candidates = [
        "#00E5FF",  # Cyan
        "#FFFFFF",  # White
        "#39FF14",  # Neon Green
    ]

    candidates = warm_candidates if is_warm_theme else cool_candidates

    # Neon Cyberpunk roads already use cyan/magenta heavily; avoid cyan route lines.
    theme_name = theme.get("name", "").lower()
    if "neon" in theme_name and "cyberpunk" in theme_name:
        candidates = [
            "#FFD400",  # Yellow
            "#FFFFFF",  # White
            "#FF6B35",  # Orange
            "#39FF14",  # Neon Green
        ]

    compare_colors = [
        theme["bg"],
        theme["road_motorway"],
        theme["road_primary"],
        theme["road_secondary"],
        theme["road_tertiary"],
        theme["road_residential"],
        theme["road_default"],
    ]

    best = candidates[0]
    best_score = -1.0
    for candidate in candidates:
        # Maximize worst-case contrast with key map layers
        score = min(_contrast_ratio(candidate, c) for c in compare_colors)
        # Require minimum acceptable contrast (4.5 for accessibility)
        if score >= 4.5 and score > best_score:
            best_score = score
            best = candidate

    return best


def project_gpx_points(points: list[tuple[float, float]], target_crs: str):
    """Project GPX points (lat, lon) from EPSG:4326 into target CRS."""
    geometry = [Point(lon, lat) for lat, lon in points]
    gs = GeoSeries(geometry, crs="EPSG:4326")
    return gs.to_crs(target_crs)


def format_start_coordinates(lat: float, lon: float) -> str:
    """Format start coordinates with cardinal direction labels."""
    lat_label = "N" if lat >= 0 else "S"
    lon_label = "E" if lon >= 0 else "W"
    return f"{abs(lat):.5f}° {lat_label}, {abs(lon):.5f}° {lon_label}"


def reverse_geocode(lat: float, lon: float) -> tuple[str, str]:
    """Reverse geocode coordinates to get city and country names.
    
    Returns (city, country) tuple. Falls back to coordinates if geocoding fails.
    """
    try:
        geolocator = Nominatim(user_agent="gpx_map_poster")
        location = geolocator.reverse(f"{lat}, {lon}", language="en")

        # Extract city and country from address
        address_parts = location.address.split(", ")

        # Common patterns for extracting city and country
        # OSM/Nominatim typically returns: street, city, county, state, country
        city = "Unknown"
        country = "Unknown"

        if location.raw.get("address"):
            addr = location.raw["address"]
            # Try to get city (city > town > village)
            city = addr.get("city") or addr.get("town") or addr.get(
                "village") or "Unknown"
            # Get country
            country = addr.get("country") or "Unknown"

        return (city, country)
    except Exception as e:
        print(f"⚠ Reverse geocoding failed: {e}")
        # Return formatted coordinates as fallback
        coords = format_start_coordinates(lat, lon)
        return ("Route Start", coords)


def create_gpx_poster(
    gpx_points: list[tuple[float, float]],
    center: tuple[float, float],
    dist: int,
    output_file: str,
    output_format: str,
    city: str,
    country: str,
    width: float,
    height: float,
    fonts,
    route_linewidth: float,
    route_padding_ratio: float,
) -> None:
    """Render a themed map poster centered on GPX route with route overlay."""
    print(
        f"\nGenerating GPX poster centered at {center[0]:.6f}, {center[1]:.6f}..."
    )

    with tqdm(total=3,
              desc="Fetching map data",
              unit="step",
              bar_format="{l_bar}{bar}| {n_fmt}/{total_fmt}") as pbar:
        pbar.set_description("Downloading street network")
        compensated_dist = get_compensated_dist(dist, width, height)
        g = cmp.fetch_graph(center, compensated_dist)
        if g is None:
            raise RuntimeError("Failed to retrieve street network data.")
        pbar.update(1)

        pbar.set_description("Downloading water features")
        water = cmp.fetch_features(
            center,
            compensated_dist,
            tags={
                "natural": ["water", "bay", "strait"],
                "waterway": "riverbank"
            },
            name="water",
        )
        pbar.update(1)

        pbar.set_description("Downloading parks/green spaces")
        parks = cmp.fetch_features(
            center,
            compensated_dist,
            tags={
                "leisure": "park",
                "landuse": "grass"
            },
            name="parks",
        )
        pbar.update(1)

    theme = cmp.THEME
    route_color = pick_route_color(theme)

    fig, ax = plt.subplots(figsize=(width, height), facecolor=theme["bg"])
    ax.set_facecolor(theme["bg"])
    ax.set_position((0.0, 0.0, 1.0, 1.0))

    g_proj = ox.project_graph(g)

    if water is not None and not water.empty:
        water_polys: GeoDataFrame = water[water.geometry.type.isin(
            ["Polygon", "MultiPolygon"])]
        if not water_polys.empty:
            try:
                water_polys = ox.projection.project_gdf(water_polys)
            except Exception:
                water_polys = water_polys.to_crs(g_proj.graph["crs"])
            water_polys.plot(ax=ax,
                             facecolor=theme["water"],
                             edgecolor="none",
                             zorder=0.5)

    if parks is not None and not parks.empty:
        parks_polys: GeoDataFrame = parks[parks.geometry.type.isin(
            ["Polygon", "MultiPolygon"])]
        if not parks_polys.empty:
            try:
                parks_polys = ox.projection.project_gdf(parks_polys)
            except Exception:
                parks_polys = parks_polys.to_crs(g_proj.graph["crs"])
            parks_polys.plot(ax=ax,
                             facecolor=theme["parks"],
                             edgecolor="none",
                             zorder=0.8)

    edge_colors = cmp.get_edge_colors_by_type(g_proj)
    edge_widths = cmp.get_edge_widths_by_type(g_proj)

    crop_xlim, crop_ylim = cmp.get_crop_limits(g_proj, center, fig,
                                               compensated_dist)
    ox.plot_graph(
        g_proj,
        ax=ax,
        bgcolor=theme["bg"],
        node_size=0,
        edge_color=edge_colors,
        edge_linewidth=edge_widths,
        show=False,
        close=False,
    )

    route_points_proj = project_gpx_points(gpx_points, g_proj.graph["crs"])
    xs = route_points_proj.x.tolist()
    ys = route_points_proj.y.tolist()

    crop_xlim, crop_ylim = expand_limits_to_include_route(
        crop_xlim,
        crop_ylim,
        xs,
        ys,
        pad_ratio=route_padding_ratio,
    )

    ax.plot(
        xs,
        ys,
        color=route_color,
        linewidth=route_linewidth,
        alpha=0.95,
        zorder=9,
        solid_capstyle="round",
        solid_joinstyle="round",
    )

    ax.set_aspect("equal", adjustable="box")
    ax.set_xlim(crop_xlim)
    ax.set_ylim(crop_ylim)

    cmp.create_gradient_fade(ax,
                             theme["gradient_color"],
                             location="bottom",
                             zorder=10)
    cmp.create_gradient_fade(ax,
                             theme["gradient_color"],
                             location="top",
                             zorder=10)

    scale_factor = min(height, width) / 12.0
    active_fonts = fonts or cmp.FONTS
    if active_fonts:
        font_main = FontProperties(fname=active_fonts["bold"],
                                   size=42 * scale_factor)
        font_sub = FontProperties(fname=active_fonts["light"],
                                  size=16 * scale_factor)
        font_coords = FontProperties(fname=active_fonts["regular"],
                                     size=12 * scale_factor)
    else:
        font_main = FontProperties(family="monospace",
                                   weight="bold",
                                   size=42 * scale_factor)
        font_sub = FontProperties(family="monospace", size=16 * scale_factor)
        font_coords = FontProperties(family="monospace",
                                     size=12 * scale_factor)

    display_city = city.upper() if cmp.is_latin_script(city) else city
    display_country = country.upper() if cmp.is_latin_script(
        country) else country
    start_lat, start_lon = gpx_points[0]
    start_coords_text = format_start_coordinates(start_lat, start_lon)

    ax.text(0.5,
            0.14,
            display_city,
            transform=ax.transAxes,
            color=theme["text"],
            ha="center",
            fontproperties=font_main,
            zorder=11)
    ax.text(0.5,
            0.10,
            display_country,
            transform=ax.transAxes,
            color=theme["text"],
            ha="center",
            fontproperties=font_sub,
            zorder=11)
    ax.text(
        0.5,
        0.07,
        start_coords_text,
        transform=ax.transAxes,
        color=theme["text"],
        alpha=0.8,
        ha="center",
        fontproperties=font_coords,
        zorder=11,
    )

    fmt = output_format.lower()
    save_kwargs = {
        "facecolor": theme["bg"],
        "bbox_inches": "tight",
        "pad_inches": 0.05
    }
    if fmt == "png":
        save_kwargs["dpi"] = 300

    plt.savefig(output_file, format=fmt, **save_kwargs)
    plt.close(fig)
    print(f"✓ Done! GPX poster saved as {output_file}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=
        "Generate a map poster from GPX route and overlay the GPX track",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python create_gpx_map_poster.py --gpx "gpx/route.gpx" --city "Georgetown" --country "Malaysia" --theme midnight_blue
  python create_gpx_map_poster.py --gpx "gpx/run.gpx" --theme ocean --distance 8000 --format svg
  python create_gpx_map_poster.py --gpx "gpx/hike.gpx"  # Auto-detect city and country
        """,
    )

    parser.add_argument("--gpx", required=True, help="Path to GPX file")
    parser.add_argument(
        "--city",
        default=None,
        help="City/title label (auto-detected from coordinates if omitted)")
    parser.add_argument(
        "--country",
        default=None,
        help=
        "Country/subtitle label (auto-detected from coordinates if omitted)")
    parser.add_argument("--theme",
                        "-t",
                        default="terracotta",
                        help="Theme name")
    parser.add_argument("--distance",
                        "-d",
                        type=int,
                        help="Map radius in meters (auto if omitted)")
    parser.add_argument("--width",
                        "-W",
                        type=float,
                        default=12,
                        help="Image width in inches")
    parser.add_argument("--height",
                        "-H",
                        type=float,
                        default=16,
                        help="Image height in inches")
    parser.add_argument(
        "--route-linewidth",
        type=float,
        default=3.4,
        help="GPX route line thickness (default: 3.4)",
    )
    parser.add_argument(
        "--route-padding-ratio",
        type=float,
        default=0.05,
        help=
        "Extra edge padding ratio to keep full route visible (default: 0.05)",
    )
    parser.add_argument("--font-family", type=str, help="Google font family")
    parser.add_argument("--format",
                        "-f",
                        default="png",
                        choices=["png", "svg", "pdf"],
                        help="Output format")
    parser.add_argument("--output", "-o", help="Output file path (optional)")
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    gpx_path = Path(args.gpx)
    if not gpx_path.exists():
        raise FileNotFoundError(f"GPX file not found: {gpx_path}")

    available_themes = cmp.get_available_themes()
    if args.theme not in available_themes:
        raise ValueError(
            f"Theme '{args.theme}' not found. Available themes: {', '.join(available_themes)}"
        )

    gpx_points = parse_gpx_points(str(gpx_path))
    center = compute_center(gpx_points)
    dist = args.distance or estimate_distance(gpx_points, center)

    if args.route_linewidth <= 0:
        raise ValueError("--route-linewidth must be greater than 0")
    if not 0 <= args.route_padding_ratio <= 1:
        raise ValueError("--route-padding-ratio must be between 0 and 1")

    print(f"✓ Loaded {len(gpx_points)} GPX points")
    print(
        f"✓ Center overridden from GPX: lat={center[0]:.6f}, lon={center[1]:.6f}"
    )
    print(f"✓ Map distance: {dist} m")
    print(f"✓ Route linewidth: {args.route_linewidth}")
    print(f"✓ Route padding ratio: {args.route_padding_ratio}")

    # Auto-detect city and country from starting coordinates if not provided
    city = args.city
    country = args.country
    if not city or not country:
        start_lat, start_lon = gpx_points[0]
        print(
            f"\nAuto-detecting location from starting coordinates ({start_lat:.6f}, {start_lon:.6f})..."
        )
        detected_city, detected_country = reverse_geocode(start_lat, start_lon)
        if not city:
            city = detected_city
        if not country:
            country = detected_country
        print(f"✓ Detected: {city}, {country}")

    cmp.THEME = cmp.load_theme(args.theme)

    custom_fonts = None
    if args.font_family:
        custom_fonts = cmp.load_fonts(args.font_family)
        if not custom_fonts:
            print(
                f"⚠ Failed to load '{args.font_family}', falling back to defaults"
            )

    output_file = args.output or cmp.generate_output_filename(
        gpx_path.stem, args.theme, args.format)

    create_gpx_poster(
        gpx_points=gpx_points,
        center=center,
        dist=dist,
        output_file=output_file,
        output_format=args.format,
        city=city,
        country=country,
        width=args.width,
        height=args.height,
        fonts=custom_fonts,
        route_linewidth=args.route_linewidth,
        route_padding_ratio=args.route_padding_ratio,
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"\n✗ Error: {exc}")
        import traceback

        traceback.print_exc()
        sys.exit(1)
