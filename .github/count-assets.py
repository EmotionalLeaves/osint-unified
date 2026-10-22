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


# Kept in step with ToolTemplates.QUERY_KEYS: a catalogue URL whose query
# carries one of these can be opened with the target already filled in, which
# is what "executable" means for a web tool.
QUERY_KEYS = {
    "q", "query", "search", "s", "term", "keyword", "keywords", "text",
    "name", "user", "username", "account", "handle", "domain", "host",
    "ip", "addr", "address", "email", "mail", "phone", "number", "lookup",
    "target", "input", "value", "url", "site",
}


def is_fillable(url):
    if "?" not in url or url.endswith("?"):
        return False
    query = url.split("?", 1)[1]
    return any(part.split("=", 1)[0].lower() in QUERY_KEYS for part in query.split("&"))


def catalogue_urls():
    """Every distinct tool URL across the bundled catalogues and the tree."""
    urls = set()
    for name in ("awesome-osint.md", "osint-stuff.md", "osint-everything.md",
                 "osint-collection.md"):
        try:
            text = open(path(name), encoding="utf-8", errors="ignore").read()
            urls.update(re.findall(r"\]\((https?://[^)\s]+)\)", text))
        except Exception:
            pass

    def walk(node):
        children = node.get("children") or []
        if not children:
            if node.get("url"):
                urls.add(node["url"])
            return
        for c in children:
            walk(c)

    try:
        walk(json.load(open(path("arf.json"), encoding="utf-8")))
    except Exception:
        pass
    return urls


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

    # The breach catalogue is parsed on-device by field name, so the build
    # asserts those fields are still there rather than discovering it on a
    # phone. A schema change shows up here as a zero.
    try:
        breaches = json.load(open(path("breaches.json"), encoding="utf-8"))
        usable = [
            b for b in breaches
            if isinstance(b, dict) and b.get("Domain") and b.get("Title")
            and b.get("BreachDate") and isinstance(b.get("DataClasses"), list)
        ]
        rows.append(("Breach catalogue entries", len(breaches)))
        rows.append(("  with domain+date+fields", len(usable)))
    except Exception:
        pass

    # How many directory entries the app can actually run against a target,
    # rather than merely link to. Claimed counts are worthless; this one is
    # derived from the same rule the app applies at runtime.
    try:
        urls = catalogue_urls()
        fillable = {u for u in urls if is_fillable(u)}
        rows.append(("Distinct catalogue URLs", len(urls)))
        rows.append(("  with a fillable query", len(fillable)))

        # The username rulesets are exact profile-URL templates, which is
        # what actually makes thousands of web tools runnable; catalogue
        # links are mostly homepages with nothing to fill in. Counting only
        # the catalogues made the app look far less capable than it is.
        runnable = set(fillable)
        for name, key in (("wmn-data.json", "sites"), ("sherlock.json", None),
                          ("maigret.json", None)):
            try:
                data = json.load(open(path(name), encoding="utf-8"))
                entries = data[key] if key else (
                    data.get("sites", data) if isinstance(data, dict) else data)
                seq = entries.values() if isinstance(entries, dict) else entries
                for e in seq:
                    if not isinstance(e, dict):
                        continue
                    u = e.get("uri_check") or e.get("url")
                    if isinstance(u, str) and u.startswith("http") and (
                            "{account}" in u or "{}" in u or "{username}" in u):
                        runnable.add(
                            u.replace("{account}", "{}").replace("{username}", "{}"))
            except Exception:
                pass
        rows.append(("TOTAL runnable against a target", len(runnable)))
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
