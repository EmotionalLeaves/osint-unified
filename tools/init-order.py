#!/usr/bin/env python3
"""A property whose initializer reads one declared further down.

Kotlin initializes an object's or a class's properties in source order,
so `val a = b.map { ... }` written above `val b = listOf(...)` compiles
and then fails with "Variable 'b' must be initialized" — or, worse in a
class, with a silent null at construction time.

It cost r427: a lookup table built in a `buildMap` that read three lists
declared below it.

Within each top-level `object` or `class` body, every property
initializer is checked against the names declared later in the same
body. Only SCREAMING_SNAKE and the project's own PascalCase table names
are considered, because a lowercase identifier inside an initializer is
far more often a lambda parameter than a sibling property, and a
checker that cries wolf is one nobody runs.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"

CONTAINER = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+)*"
    r"(object|class)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)

# `val NAME ... =` at one level of indentation inside the body.
PROPERTY = re.compile(
    r"^(\s+)(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|const\s+|@Volatile\s+|lateinit\s+)*"
    r"val\s+([A-Z][A-Z0-9_]{2,})\s*(?::[^=\n]+)?=",
    re.M,
)


def without_prose(text: str) -> str:
    """Comments and string bodies blanked, newlines kept.

    The first version of this script reported its own explanatory
    comment, which names the construct it exists to find. Line numbers
    have to survive, so prose is replaced space for space rather than
    removed.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif text[i] == '"':
            triple = text[i:i + 3] == '"""'
            close = '"""' if triple else '"'
            j = i + len(close)
            while j < n:
                if not triple and text[j] == "\\":
                    j += 2
                    continue
                if text[j:j + len(close)] == close:
                    j += len(close)
                    break
                j += 1
            for k in range(i, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)
DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+"
    r"|data\s+|value\s+|annotation\s+|enum\s+)*"
    r"(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)


def body_of(text: str, open_brace: int) -> tuple[str, int]:
    depth = 0
    for i in range(open_brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1:i], open_brace + 1
    return "", open_brace + 1


def initializer(body: str, start: int) -> str:
    """The text of one property's initializer, to its blank line or next decl."""
    depth = 0
    i = start
    seen = False
    while i < len(body):
        c = body[i]
        if c in "({[":
            depth += 1
            seen = True
        elif c in ")}]":
            depth -= 1
        elif c == "\n" and depth <= 0 and seen:
            break
        elif c == "\n" and depth <= 0 and not seen:
            # A single-line initializer with no brackets.
            break
        i += 1
    return body[start:i]


def main() -> int:
    bad: list[str] = []
    for path in sorted(SRC.rglob("*.kt")):
        # Comments and string bodies blanked, newlines kept: a name
        # explained in a comment above a list, or written inside one of
        # its strings, is not a reference to it.
        text = without_prose(path.read_text(errors="replace"))
        for m in CONTAINER.finditer(text):
            brace = text.find("{", m.end())
            if brace < 0:
                continue
            body, offset = body_of(text, brace)
            props = list(PROPERTY.finditer(body))
            if len(props) < 2:
                continue
            for index, prop in enumerate(props):
                later = {p.group(2) for p in props[index + 1:]}
                if not later:
                    continue
                init = initializer(body, prop.end())
                for name in sorted(later):
                    if re.search(r"\b%s\b" % re.escape(name), init):
                        line = text.count("\n", 0, offset + prop.start()) + 1
                        bad.append(
                            f"{path.relative_to(ROOT)}:{line}: "
                            f"{m.group(2)}.{prop.group(2)} reads {name}, which is "
                            f"declared below it — Kotlin initializes in source order"
                        )
    if bad:
        print("Properties initialized from something that does not exist yet:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print("init-order: every property initializer reads only what is already there.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
