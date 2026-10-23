"""Turns Natural Earth's land polygons into something a phone can draw.

The globe draws its own coastlines rather than fetching map tiles: a
sphere cannot be textured with flat tiles without reprojecting every one
of them, and the point of the thing is to see the whole Earth at once,
which tiles are the wrong shape for.

The source is 110m Natural Earth, the coarsest they publish, which is
right for a globe a few hundred pixels across. It is rounded to two
decimal places — about a kilometre, and well under a pixel at any zoom
this draws at — and flattened into alternating lon/lat so the parser on
the other end is a loop over numbers rather than a JSON tree per point.
"""
import json
import sys

PRECISION = 2
MIN_RING_POINTS = 4


def rings(geometry):
    kind = geometry.get("type")
    if kind == "Polygon":
        return geometry.get("coordinates", [])
    if kind == "MultiPolygon":
        return [ring for poly in geometry.get("coordinates", []) for ring in poly]
    return []


def compact(ring):
    out = []
    last = None
    for point in ring:
        try:
            lon = round(float(point[0]), PRECISION)
            lat = round(float(point[1]), PRECISION)
        except (TypeError, ValueError, IndexError):
            continue
        if not (-180.0 <= lon <= 180.0 and -90.0 <= lat <= 90.0):
            continue
        if (lon, lat) == last:
            continue
        last = (lon, lat)
        out.extend((lon, lat))
    return out if len(out) >= MIN_RING_POINTS * 2 else None


def main(source, destination):
    with open(source, encoding="utf-8") as f:
        data = json.load(f)
    polygons = []
    for feature in data.get("features", []):
        for ring in rings(feature.get("geometry") or {}):
            flattened = compact(ring)
            if flattened:
                polygons.append(flattened)
    # Biggest first: a globe that is still loading should show Eurasia
    # before it shows Jan Mayen.
    polygons.sort(key=len, reverse=True)
    with open(destination, "w", encoding="utf-8") as f:
        json.dump({"rings": polygons}, f, separators=(",", ":"))
    points = sum(len(p) for p in polygons) // 2
    print(f"coastlines: {len(polygons)} rings, {points} points")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
