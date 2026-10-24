#!/usr/bin/env python3
"""A public declaration whose signature names an internal type.

Kotlin refuses "public function exposes its internal parameter type", and
the local check cannot see it: every unresolved name is treated as
missing-SDK noise, so the file compiles here and dies in CI. That is how
r509 died, over a class that had no modifier and a parameter type that
did.

This is the third shape of the same mistake. object-members.py already
catches a private companion read from another file, and a visible
companion member with a private-in-class type. Both were found the
expensive way, one commit apart, and so was this one.

The rule: a top-level declaration with no visibility modifier is public,
and a public signature may not name a type declared `internal` anywhere
in the project. Opt out with `// visibility-leak: <why>` on the line
above.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main"

INTERNAL_TYPE = re.compile(
    r"^\s*internal\s+(?:data\s+|sealed\s+|value\s+|enum\s+|annotation\s+|abstract\s+|open\s+)*"
    r"(?:class|interface|object|typealias)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)

# Any declaration, at any indent. Members of a public class are public
# too — which is the half the first draft of this check missed, on the
# exact failure it was written for: `class TileStore` is top level and
# `fun peek(ref: Tiles.Ref)` is two levels in, and it was the member
# that CI refused.
DECL = re.compile(
    r"^([ \t]*)((?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:expect\s+|actual\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+|enum\s+"
    r"|annotation\s+|inline\s+|suspend\s+|override\s+|operator\s+|infix\s+|const\s+"
    r"|lateinit\s+|external\s+|tailrec\s+)*"
    r"(class|interface|object|fun|val|var)\s+)",
    re.M,
)

HIDDEN = re.compile(r"\b(?:private|internal|protected)\b")


def signature_at(code: str, start: int) -> str:
    """From a declaration keyword to the end of its signature.

    Stops at the body, the initialiser, or the end of the line when
    there is neither — and follows parentheses across lines, because a
    parameter list formatted one-per-line is the normal shape in this
    project and reading only the first line of it sees no types at all.
    """
    depth = 0
    i = start
    n = len(code)
    while i < n:
        c = code[i]
        # Parentheses and square brackets only. Angle brackets are
        # not brackets in Kotlin: `(Double, Double) -> Unit` closes
        # one that was never opened, depth went negative, the body
        # brace stopped being a stop, and the "signature" ran to the
        # end of the function — which is how the first run of this
        # check reported four composables for naming a type in their
        # own bodies.
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        elif depth == 0:
            if c in "{=;":
                break
            if c == "\n":
                # A signature continues onto the next line only while
                # something is still open, or the line ended on a colon
                # or comma.
                if code[max(start, i - 2):i].rstrip().endswith((":", ",")):
                    i += 1
                    continue
                break
        i += 1
    return code[start:i]


def hidden_spans(code: str) -> list[tuple[int, int]]:
    """Character ranges belonging to a private or internal container.

    Anything inside one is already invisible from outside the module,
    so a signature in there may name whatever it likes.
    """
    spans: list[tuple[int, int]] = []
    for m in re.finditer(
        r"^[ \t]*(?:private|internal)\s+(?:[a-z]+\s+)*(?:class|interface|object)\b",
        code, re.M
    ):
        brace = code.find("{", m.end())
        if brace < 0:
            continue
        depth = 0
        for i in range(brace, len(code)):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    spans.append((m.start(), i))
                    break
    return spans


def main() -> int:
    files = sorted(SRC.rglob("*.kt"))
    internal: set[str] = set()
    for path in files:
        internal.update(INTERNAL_TYPE.findall(without_prose(path.read_text(errors="replace"))))
    if not internal:
        print("visibility-leak: no internal types declared, nothing to check.")
        return 0

    bad: list[str] = []
    for path in files:
        raw = path.read_text(errors="replace")
        code = without_prose(raw)
        raw_lines = raw.split("\n")
        hidden = hidden_spans(code)
        for m in DECL.finditer(code):
            if HIDDEN.search(m.group(2)):
                continue
            if any(a <= m.start() <= b for a, b in hidden):
                continue
            # Top level, or one level in — which is a member of a
            # top-level class, object or interface, and public with
            # it. Anything deeper is a local inside a body, where
            # visibility is not a thing and naming an internal type
            # is ordinary.
            if len(m.group(1).expandtabs(4)) > 4:
                continue
            line = code.count("\n", 0, m.start()) + 1
            if line >= 2 and "visibility-leak:" in raw_lines[line - 2]:
                continue
            signature = signature_at(code, m.end())
            # The declared name itself is not a use of a type.
            own = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)", signature)
            own_name = own.group(1) if own else ""
            named = {
                ty for ty in internal
                if ty != own_name and re.search(r"\b%s\b" % re.escape(ty), signature)
            }
            if named:
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: public declaration names the "
                    f"internal type {sorted(named)[0]} — Kotlin refuses that"
                )

    if bad:
        print("Public signatures that name an internal type:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(
        f"visibility-leak: {len(internal)} internal types, "
        f"no public signature names one."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
