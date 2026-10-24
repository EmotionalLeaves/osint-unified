#!/usr/bin/env python3
"""An empty list must mean "there is nothing", never "I could not ask".

This is the defect this project has found and fixed more often than
any other, in more different files than any other, and it keeps
coming back because the wrong version is three characters shorter
than the right one:

    ?: return emptyList()        vs   ?: throw IOException("...")

A layer's count is read out loud. "PORTS·GLOBAL 0" is the app
stating that there are no ports in the world; "no tropical cyclones
anywhere" is printed from an empty list; "No Wikipedia places within
10 km" is classified as an established absence and counted as a
source that looked. Every one of those sentences has been printed in
anger because a parse failure or a 403 became an empty list
somewhere a reviewer was not reading.

So: in the feed sources, a function that produces the layer's data
may not hand back an empty list for a body it could not read. It has
to raise, and the poller turns that into "not reporting".

What this cannot see, and does not try to: whether an empty list is
*correct*. A genuinely quiet ocean returns one and must. The test
here is narrower and mechanical — an empty list produced on the
failure branch of a parse or an HTTP check.

Opting out, where the empty list really is the answer:

    // empty-is-an-answer: <why>
    return emptyList()

The note is required and is read by the next person, so "it is
fine" is not one.
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from ktscan import without_prose  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / "app/src/main/java/com/osint/unified/data"

# The shapes that mean "I could not read this, so: nothing".
#
# An early return, not a value default: `?: emptyList()` at the end
# of an expression is usually an optional field with no entries,
# which is a different thing and a legitimate one.
FAILURE_EMPTY = [
    re.compile(r"\?:\s*return@?\w*\s+emptyList\(\)"),
    re.compile(r"catch\s*\([^)]*\)\s*\{\s*emptyList\(\)"),
]

# `getOrDefault(emptyList())` is how a merged source shows what it
# did get while one of its agencies is down — which is right, and
# is only right when the source can also say which one was missing.
# A source that merges away a failure and cannot name it is the
# feedTrouble ceiling all over again.
MERGE_AWAY = re.compile(r"getOrDefault\(emptyList\(\)\)")

# A guard clause that returns empty when a response was refused.
REFUSAL_EMPTY = re.compile(
    r"if\s*\([^)]*(?:isSuccessful|isNullOrBlank|code\s*[!=]=|== *null)[^)]*\)\s*"
    r"return@?\w*\s+emptyList\(\)"
)

OPT_OUT = re.compile(r"//\s*empty-is-an-answer:\s*\S")


def main() -> int:
    if not DATA.is_dir():
        print("empty-is-an-answer: no data directory, nothing to check.")
        return 0
    findings = []
    checked = 0
    for path in sorted(DATA.glob("*.kt")):
        raw = path.read_text()
        # Only the feed sources. OsintFramework and the settings are
        # not layers and their empty lists are not claims.
        if "DataSource" not in path.name:
            continue
        checked += 1
        code = without_prose(raw)
        lines = raw.split("\n")
        code_lines = code.split("\n")
        says_who = "PartlyAnswered" in code
        for i, line in enumerate(code_lines):
            hit = any(p.search(line) for p in FAILURE_EMPTY) or REFUSAL_EMPTY.search(line)
            if not hit and MERGE_AWAY.search(line) and not says_who:
                hit = True
                why = "merges a failure away without implementing PartlyAnswered"
            elif hit:
                why = "returns an empty list on a failure path"
            if not hit:
                continue
            # The opt-out may sit on the line or in the comment
            # block above it, and the reason is usually a sentence
            # rather than a phrase.
            window = "\n".join(lines[max(0, i - 6):i + 1])
            if OPT_OUT.search(window):
                continue
            findings.append((path.relative_to(ROOT), i + 1, lines[i].strip(), why))

    if not findings:
        print(
            "empty-is-an-answer: %d feed sources checked, "
            "no failure path returns an empty list." % checked
        )
        return 0
    print("empty-is-an-answer: a failure is being returned as an empty list.")
    print("  An empty list is the app saying there is nothing out there.")
    print("  Raise instead, or mark it `// empty-is-an-answer: <why>`.")
    for rel, line, text, why in findings:
        print("  %s:%d  %s" % (rel, line, text))
        print("      %s" % why)
    return 1


if __name__ == "__main__":
    sys.exit(main())
