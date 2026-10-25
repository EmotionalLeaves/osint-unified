"""Packs Natural Earth lines into a binary file the globe reads directly.

The detailed border sets are millions of numbers. As JSON a phone spends
seconds and a tree of objects per number reading them; as a run of
32-bit floats it is one read into an array.

Format, little-endian:
    b"LNS1", int32 line count,
    then per line: int32 point count, then that many (lon, lat) float32 pairs.

Coordinates are rounded to four decimal places (about ten metres), and
repeated points dropped. Lines shorter than two points are not lines.
"""
import json
import struct
import sys

PRECISION = 4


def lines(geometry):
    kind = geometry.get("type")
    if kind == "LineString":
        return [geometry.get("coordinates", [])]
    if kind == "MultiLineString":
        return geometry.get("coordinates", [])
    if kind == "Polygon":
        return geometry.get("coordinates", [])
    if kind == "MultiPolygon":
        return [ring for poly in geometry.get("coordinates", []) for ring in poly]
    return []


def main(source, destination):
    with open(source, encoding="utf-8") as f:
        data = json.load(f)
    packed = []
    total = 0
    for feature in data.get("features", []):
        for line in lines(feature.get("geometry") or {}):
            pts = []
            last = None
            for p in line:
                try:
                    lon = round(float(p[0]), PRECISION)
                    lat = round(float(p[1]), PRECISION)
                except (TypeError, ValueError, IndexError):
                    continue
                if not (-180.0 <= lon <= 180.0 and -90.0 <= lat <= 90.0):
                    continue
                if (lon, lat) == last:
                    continue
                last = (lon, lat)
                pts.append((lon, lat))
            if len(pts) >= 2:
                packed.append(pts)
                total += len(pts)
    with open(destination, "wb") as f:
        f.write(b"LNS1")
        f.write(struct.pack("<i", len(packed)))
        for pts in packed:
            f.write(struct.pack("<i", len(pts)))
            f.write(struct.pack("<%df" % (2 * len(pts)), *[c for p in pts for c in p]))
    print(f"{destination}: {len(packed)} lines, {total} points")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
