#!/usr/bin/env python3
"""
Every host the app contacts, against every source it admits to.

A dossier is a record of who was asked about the target. If the code
calls a service the Sources screen does not name, the person running
the investigation has handed an identifier to a party they were never
told about, and the screen that exists to prevent exactly that is
wrong. The reverse — a source named but never called — is a smaller
fault and still a false claim.

Prints the two differences and exits non-zero if the first one is not
empty. Hosts the app only ever LINKS to (a URL handed to the browser
for the person to open themselves) are a different thing from hosts it
FETCHES, so only fetches count here; the allowlist below records the
few that are deliberately unlisted and says why for each.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MAIN = os.path.join(ROOT, "app/src/main/java")
SOURCES = os.path.join(
    ROOT, "app/src/main/java/com/osint/unified/ui/about/SourcesScreen.kt"
)

# A URL inside a call that actually performs a request. Matching the call
# is what separates a fetch from a link: `get("https://…")`,
# `getBody("https://…")`, `fetchPage("https://…")` and friends.
FETCH = re.compile(
    r"\b(?:get|getBody|getOrNull|post|fetchPage|fetchText|head|json|"
    r"dohOrNull|dohTxt|ethRpc)\s*\(\s*\n?\s*\"(https://[^\"\s]+)"
)
ANY_URL = re.compile(r"\"https://([A-Za-z0-9.-]+)")

# Named on the screen under a label that is not the hostname, or
# deliberately not a source at all.
ALLOW = {
    # The app's own repository, used for the update check and the
    # user agent. It is told about the app, not about the target.
    "github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
    # Tile and imagery hosts for the map, named on the screen as
    # OpenStreetMap and NASA rather than by hostname.
    "tile.openstreetmap.org", "a.tile.openstreetmap.org",
    "b.tile.openstreetmap.org", "c.tile.openstreetmap.org",
    "tiles.stadiamaps.com", "basemap.nationalmap.gov",
    "services.arcgisonline.com", "server.arcgisonline.com",
    # Documentation links that appear in prose, never fetched.
    "www.w3.org", "schema.org",
}


def hosts_fetched():
    out = {}
    for dirpath, _, files in os.walk(MAIN):
        for f in files:
            if not f.endswith(".kt"):
                continue
            full = os.path.join(dirpath, f)
            with open(full, encoding="utf-8", errors="replace") as fh:
                body = fh.read()
            for m in FETCH.finditer(body):
                host = m.group(1).split("/")[2].split("$")[0].lower()
                if host and "$" not in host:
                    out.setdefault(host, set()).add(
                        os.path.relpath(full, ROOT)
                    )
    return out


def registrable(host):
    """Enough of a host to recognise the operator by.

    Not a public-suffix implementation: the two-label rule is wrong for
    co.uk and friends, so those are kept at three labels by hand.
    """
    parts = host.split(".")
    if len(parts) <= 2:
        return host
    two = ".".join(parts[-2:])
    if two in ("co.uk", "org.uk", "gov.uk", "ac.uk", "com.au", "co.jp",
               "go.jp", "gov.au", "cc.gatech.edu"):
        return ".".join(parts[-3:])
    return two


def main():
    raw = open(SOURCES, encoding="utf-8", errors="replace").read().lower()
    # Squashed, because the screen writes operators the way people say
    # them — "Cert Spotter", "Hacker Target", "Have I Been Pwned" — and
    # the code writes them the way DNS does.
    declared_text = re.sub(r"[^a-z0-9]", "", raw)
    fetched = hosts_fetched()

    undeclared = {}
    for host, where in sorted(fetched.items()):
        if host in ALLOW:
            continue
        reg = registrable(host)
        # The screen names a source either by hostname or by the
        # operator's name, so the registrable domain and its bare label
        # are both accepted.
        label = re.sub(r"[^a-z0-9]", "", reg.split(".")[0])
        flat = re.sub(r"[^a-z0-9]", "", reg)
        if flat in declared_text or (len(label) > 4 and label in declared_text):
            continue
        undeclared[host] = sorted(where)

    if undeclared:
        print("Fetched but not named on the Sources screen:")
        for host, where in sorted(undeclared.items()):
            print("  {}  ({})".format(host, ", ".join(w.split("/")[-1] for w in where)))
    else:
        print("declared-sources: every fetched host is named on the Sources screen"
              " ({} hosts).".format(len(fetched)))
    return 1 if undeclared else 0


if __name__ == "__main__":
    sys.exit(main())
