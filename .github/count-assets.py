"""Counts what is actually inside each bundled ruleset and catalogue.

The tool count the app advertises should be measured, not claimed, so CI
runs this over app/src/main/assets and reports the result as a build
annotation. Every lookup is defensive: a dataset whose mirror went down is
simply absent from the report rather than breaking the build.
"""
import json
import os
import re
import sys

ASSETS = "app/src/main/assets"


def path(name):
    return os.path.join(ASSETS, name)


def count_json_sites(name, key=None):
    data = json.load(open(path(name), encoding="utf-8"))
    if key:
        return len(data[key])
    return len(data.get("sites", data)) if isinstance(data, dict) else len(data)


def count_markdown_links(name):
    text = open(path(name), encoding="utf-8", errors="ignore").read()
    return len(set(re.findall(r"\]\((https?://[^)\s]+)\)", text)))


def count_tree_leaves(node):
    children = node.get("children") or []
    if not children:
        return 1 if node.get("url") else 0
    return sum(count_tree_leaves(c) for c in children)


def main():
    rows = []

    for label, name, key in [
        ("WhatsMyName sites", "wmn-data.json", "sites"),
        ("Sherlock sites", "sherlock.json", None),
        ("Maigret sites", "maigret.json", None),
    ]:
        try:
            rows.append((label, count_json_sites(name, key)))
        except Exception:
            pass

    for label, name in [
        ("awesome-osint links", "awesome-osint.md"),
        ("osint_stuff links", "osint-stuff.md"),
        ("awesome-osint-everything links", "osint-everything.md"),
        ("OSINT_Collection links", "osint-collection.md"),
    ]:
        try:
            rows.append((label, count_markdown_links(name)))
        except Exception:
            pass

    try:
        rows.append(
            ("OSINT Framework entries",
             count_tree_leaves(json.load(open(path("arf.json"), encoding="utf-8"))))
        )
    except Exception:
        pass

    username_total = sum(v for k, v in rows if "sites" in k)
    if username_total:
        rows.append(("TOTAL username site rules", username_total))
    catalogue_total = sum(v for k, v in rows if "links" in k or "entries" in k)
    if catalogue_total:
        rows.append(("TOTAL catalogue entries", catalogue_total))

    sys.stdout.write("%0A".join("{}: {}".format(k, v) for k, v in rows))


if __name__ == "__main__":
    main()
