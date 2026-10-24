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

# The shapes that mean "I could not read this, so: nothing".
#
# Matched over the whole file, not line by line. The first version
# read one line at a time, so the idiomatic spelling of its own bug —
#
#     } catch (e: Exception) {
#         emptyList()
#     }
#
# — was invisible, and so was every guard whose `return` sat on the
# line after its `if`. `\s` spans newlines; a line-at-a-time scan
# never gave it one to span.
NOTHING = r"(?:emptyList|emptyMap|emptySet|listOf|mapOf|setOf)(?:<[^<>]{0,80}>)?\(\s*\)"
FAILURE_EMPTY = [
    # An early return on a null: `?: return emptyList()`.
    re.compile(r"\?:\s*return@?\w*\s+" + NOTHING),
    # A catch whose whole body is "nothing".
    re.compile(r"catch\s*\([^)]*\)\s*\{\s*(?:return@?\w*\s+)?" + NOTHING + r"\s*\}"),
    # runCatching { ... }.getOrElse { nothing }
    re.compile(r"getOrElse\s*\{\s*(?:\w+\s*->\s*)?(?:return@?\w*\s+)?" + NOTHING + r"\s*\}"),
    # The same thing spelled with getOrNull, with or without a return:
    # `runCatching { parse(body) }.getOrNull() ?: emptyList()`.
    re.compile(r"getOrNull\(\)\s*\?:\s*(?:return@?\w*\s+)?" + NOTHING),
    # A function whose name says it returns null when it fails, with
    # the failure coalesced straight to nothing: `fooOrNull(x) ?:
    # emptyList()`. A bare `?: emptyList()` after a missing JSON field
    # is usually the field meaning "none" and is not matched; this is
    # the shape where null can only mean "could not".
    re.compile(r"\w+OrNull\((?:[^()]|\([^()]*\))*\)\s*\?:\s*(?:return@?\w*\s+)?" + NOTHING),
    # A catch that does something first — logs, records the error —
    # and then hands back nothing. The whole-body pattern above only
    # saw a catch that was *only* the empty list.
    re.compile(r"catch\s*\([^)]*\)\s*\{[^{}]{0,400}?(?:return@?\w*\s+)?" + NOTHING + r"\s*\}"),
]

# `getOrDefault(emptyList())` is how a merged source shows what it
# did get while one of its agencies is down — which is right, and
# is only right when the source can also say which one was missing.
# A source that merges away a failure and cannot name it is the
# feedTrouble ceiling all over again.
MERGE_AWAY = re.compile(r"getOrDefault\(\s*" + NOTHING + r"\s*\)")

# A guard clause that returns nothing when a response was refused.
# The condition may hold its own parentheses — `body.isNullOrBlank()`
# — which `[^)]*` stopped at, so most real guards never matched.
REFUSAL_EMPTY = re.compile(
    r"if\s*\((?:[^()]|\([^()]*\)){0,40}?"
    r"(?:isSuccessful|isNullOrBlank|isNullOrEmpty|code\s*[!=]=|==\s*null)"
    r"(?:[^()]|\([^()]*\)){0,40}?\)\s*\{?\s*return@?\w*\s+" + NOTHING
)

OPT_OUT = re.compile(r"//\s*empty-is-an-answer:\s*\S")
FUN = re.compile(r"^\s*(?:@\w+\s+)*(?:(?:private|internal|public|protected|override|suspend|inline)\s+)*fun\b")

# Where to look. It was every file in data/ whose name contained
# "DataSource" — two filters that left the whole of domain/executor,
# where most of this project's false absences have actually lived,
# unread, and exempted any new source named anything else. Now: every
# file under data/ and domain/executor/ that talks to the network.
SCOPES = ["app/src/main/java/com/osint/unified/data",
          "app/src/main/java/com/osint/unified/domain/executor"]
TALKS = re.compile(r"\.newCall\(|\bexecute\(\)|\bget\(\s*\"https?:|getBody\(|getOrBody\(|fetchPage\(")


def main() -> int:
    findings = []
    checked = 0
    for scope in SCOPES:
        base = ROOT / scope
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.kt")):
            raw = path.read_text()
            code = without_prose(raw)
            if not TALKS.search(code):
                continue
            checked += 1
            lines = raw.split("\n")
            code_lines = code.split("\n")
            says_who = "PartlyAnswered" in code
            hits = []
            for pat in FAILURE_EMPTY + [REFUSAL_EMPTY]:
                for m in pat.finditer(code):
                    hits.append((m.start(), "returns nothing on a failure path"))
            if not says_who:
                for m in MERGE_AWAY.finditer(code):
                    hits.append((m.start(), "merges a failure away without implementing PartlyAnswered"))
            for offset, why in sorted(set(hits)):
                line = code.count("\n", 0, offset)
                # The opt-out may sit on the line or in the comment
                # block above it, and the reason is usually a sentence
                # rather than a phrase.
                window = "\n".join(lines[max(0, line - 8):line + 2])
                if OPT_OUT.search(window):
                    continue
                # Or once, above the function it is in: a parser whose
                # caller reads its empty list as a gap says so on the
                # function, not on every return inside it.
                fn = line
                while fn > 0 and not FUN.match(code_lines[fn]):
                    fn -= 1
                if FUN.match(code_lines[fn]) and OPT_OUT.search(
                    "\n".join(lines[max(0, fn - 4):fn])
                ):
                    continue
                findings.append((path.relative_to(ROOT), line + 1, lines[line].strip(), why))

    if not findings:
        print(
            "empty-is-an-answer: %d network-facing files checked, "
            "no failure path returns an empty answer." % checked
        )
        return 0
    print("empty-is-an-answer: a failure is being returned as an empty answer.")
    print("  An empty list is the app saying there is nothing out there.")
    print("  Raise instead, or mark it `// empty-is-an-answer: <why>`.")
    for rel, line, text, why in findings:
        print("  %s:%d  %s" % (rel, line, text[:100]))
        print("      %s" % why)
    return 1


if __name__ == "__main__":
    sys.exit(main())
