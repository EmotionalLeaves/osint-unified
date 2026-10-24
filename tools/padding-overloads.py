#!/usr/bin/env python3
"""
Modifier.padding has two overloads and they do not mix.

padding(start, top, end, bottom) and padding(horizontal, vertical)
are separate functions, so padding(horizontal = 12.dp, bottom =
4.dp) resolves to neither and fails with "None of the following
candidates is applicable" — pointing at a column, with no hint
that the two names come from different overloads. It cost a CI
round trip, and kotlinc cannot say it here because the symbol
needs the Android SDK.
"""
import re
import sys
from pathlib import Path

EDGE = {"start", "top", "end", "bottom"}
AXIS = {"horizontal", "vertical"}
CALL = re.compile(r"\bpadding\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)")
NAMED = re.compile(r"(\w+)\s*=")


def main(roots):
    bad = 0
    files = 0
    for root in roots:
        for path in sorted(Path(root).rglob("*.kt")):
            files += 1
            for n, line in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
                for m in CALL.finditer(line.split("//", 1)[0]):
                    names = set(NAMED.findall(m.group(1)))
                    if names & EDGE and names & AXIS:
                        print("%s:%d: padding() mixes %s with %s — those are "
                              "two different overloads"
                              % (path, n, sorted(names & AXIS), sorted(names & EDGE)))
                        bad += 1
    if bad:
        print("\n%d padding() call(s) that will not resolve." % bad)
        return 1
    print("padding-overloads: %d files checked, no mixed padding() calls."
          % files)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["app/src/main/java"]))
