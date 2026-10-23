"""Checks that each bundled dataset is what it claims to be, and records it.

Every one of these files is downloaded at build time from a third party's
main branch with `|| true`, so that a dead mirror cannot break the build.
The cost of that is a build which happily ships whatever came back —
an error page, a truncated JSON file, an empty ruleset — and the app then
reports "no accounts found" with great confidence because it had no rules
to check against.

So each file is parsed and measured here. A dataset the app is allowed to
be without is deleted when it does not pass, because a missing dataset is
handled and a corrupt one is not. A dataset the app must not lie about —
the sanctions lists, the account ruleset — fails the build instead.

Everything that survives is hashed into assets/dataset-checksums.txt, so
the build records exactly which snapshot of each third-party list it
shipped, and the same file goes into the APK where it can be read back.
"""
import hashlib
import json
import os
import sys
import xml.etree.ElementTree as ET

ASSETS = "app/src/main/assets"


def full(name):
    return os.path.join(ASSETS, name)


def json_count(name):
    with open(full(name), encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict):
        # "rings" is the globe's coastlines, which are an object
        # with one key — so counting the keys called a complete file
        # one entry and the new floor deleted it.
        for key in ("sites", "breaches", "data", "rings"):
            if isinstance(data.get(key), (list, dict)):
                return len(data[key])
        return len(data)
    return len(data)


def xml_tags(name, tag):
    count = 0
    for _, element in ET.iterparse(full(name), events=("end",)):
        if element.tag.rsplit("}", 1)[-1] == tag:
            count += 1
        element.clear()
    return count


def csv_rows(name):
    with open(full(name), encoding="utf-8", errors="replace") as f:
        return sum(1 for line in f if line.strip())


def markdown_links(name):
    with open(full(name), encoding="utf-8", errors="replace") as f:
        text = f.read()
    return text.count("](http")


# name -> (how to measure it, the least it may hold, may the build go on
# without it)
def jpeg_bytes(name: str) -> int:
    """Size, after checking it is actually a JPEG.

    An optional asset that fails this is deleted rather than shipped,
    and the globe falls back to drawn coastlines — which is the right
    outcome for a texture that is secretly an HTML error page, because
    the decoder would simply return null and the sphere would be black.
    """
    with open(full(name), "rb") as f:
        head = f.read(3)
    if head != b"\xff\xd8\xff":
        raise ValueError("not a JPEG")
    return os.path.getsize(full(name))


CHECKS = {
    "sdn.csv": (csv_rows, 5_000, False),
    "sdn-alt.csv": (csv_rows, 5_000, False),
    "wmn-data.json": (json_count, 200, False),
    "sherlock.json": (json_count, 100, True),
    "maigret.json": (json_count, 100, True),
    "un-sanctions.xml": (lambda n: xml_tags(n, "INDIVIDUAL"), 100, True),
    "breaches.json": (json_count, 100, True),
    "awesome-osint.md": (markdown_links, 200, True),
    "osint-stuff.md": (markdown_links, 200, True),
    "osint-everything.md": (markdown_links, 200, True),
    "osint-collection.md": (markdown_links, 200, True),
    "airports.json": (json_count, 2_000, True),
    "powerplants.json": (json_count, 10_000, True),
    "coastlines.json": (json_count, 50, True),
    "borders.json": (json_count, 50, True),
    "country-centroids.json": (json_count, 150, True),
    "ports.json": (json_count, 1_000, True),
    # An image, so "entries" is bytes. A JPEG that arrived as an error
    # page is a few kilobytes; a real Blue Marble downscale is hundreds.
    "earth-day.jpg": (jpeg_bytes, 200_000, True),
    "earth-night.jpg": (jpeg_bytes, 40_000, True),
}


def main() -> int:
    failed = []
    report = []

    for name, (measure, floor, optional) in CHECKS.items():
        if not os.path.exists(full(name)):
            if optional:
                report.append(f"{name}: absent")
                continue
            failed.append(f"{name} is missing and the app must not ship without it")
            continue
        try:
            held = measure(name)
        except Exception as exc:
            held = -1
            why = f"{name} did not parse ({type(exc).__name__}: {exc})"
        else:
            why = None if held >= floor else (
                f"{name} holds {held} entries, fewer than the {floor} a real "
                f"copy of it has — it is truncated or it is an error page"
            )
        if why is None:
            report.append(f"{name}: {held} entries")
            continue
        if optional:
            # Deleted rather than shipped: the app handles an absent dataset
            # and says so, and cannot tell that a present one is nonsense.
            os.remove(full(name))
            print(f"::warning title=dataset-dropped::{why}")
            report.append(f"{name}: dropped — {why}")
        else:
            failed.append(why)

    # Hash whatever is going into the APK, including the files no check
    # covers, so the build has a record of the exact bytes it shipped.
    lines = []
    for name in sorted(os.listdir(ASSETS)):
        path = os.path.join(ASSETS, name)
        if not os.path.isfile(path) or name == "dataset-checksums.txt":
            continue
        digest = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                digest.update(chunk)
        lines.append(f"{digest.hexdigest()}  {os.path.getsize(path):>10}  {name}")
    with open(full("dataset-checksums.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print("\n".join(lines))
    print("::notice title=dataset-shape::" + "%0A".join(report))

    if failed:
        for why in failed:
            print(f"::error title=dataset-broken::{why}")
        print(
            "::error::Refusing to ship a build whose required datasets are "
            "missing or corrupt: the app would answer 'nothing found' to "
            "every question it cannot actually ask."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
