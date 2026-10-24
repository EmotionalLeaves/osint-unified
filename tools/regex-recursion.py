#!/usr/bin/env python3
"""No repeated group in a regex that ever sees untrusted text.

`java.util.regex` — which is what Kotlin's `Regex` compiles to —
repeats a *group* recursively, one stack frame per iteration. So a
pattern of the form `(?:...)+` or `(?:...)*` over an input that can
be long throws StackOverflowError rather than taking a long time,
and `StackOverflowError` is an `Error`, which nothing in Compose
catches.

The textbook hostname pattern

    \\b(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}\\b

was written in five files here. Measured on this project's own
toolchain it survives 1,500 dotted labels and overflows at 2,000 —
four kilobytes. The scan ran during composition, on the main
thread, over output straight out of the database, so four kilobytes
of dotted labels in any stored result crashed the app every time
that case was opened, for ever, from content a subject could
choose. See `domain/Hostnames.kt`.

A bounded character-class repetition — `[A-Za-z0-9.-]{0,253}` — is
compiled to an iterative matcher and is safe. A group repetition
with a bounded count, `(?:ab){1,3}`, is also fine. What this looks
for is an *unbounded* group repetition: `)+`, `)*`, `){2,}`.

Opting out, where the input is short and fixed:

    // regex-recursion: <why>

The note is required and is read by the next person.
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from ktscan import without_prose  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

# A closing paren followed by an unbounded repetition. `)?` and
# `){0,3}` are bounded and fine; `)+`, `)*`, `){2,}` are not.
UNBOUNDED_GROUP = re.compile(r"\)\s*(?:[+*]|\{\d+,\})(?!\})")
OPT_OUT = re.compile(r"//\s*regex-recursion:\s*\S")

# Raw strings first, so their contents are not re-scanned as
# ordinary ones. Both forms carry a line number by construction.
RAW = re.compile(r'"""(.*?)"""', re.S)
PLAIN = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def literals(text):
    """Every string literal, with the line it starts on."""
    out = []
    spans = []
    for m in RAW.finditer(text):
        spans.append(m.span())
        out.append((text.count("\n", 0, m.start()) + 1, m.group(1)))
    for m in PLAIN.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        out.append((text.count("\n", 0, m.start()) + 1, m.group(1)))
    return out


def looks_like_a_pattern(body):
    """A string that is doing regex work rather than holding prose."""
    if len(body) < 4:
        return False
    marks = ("\\d", "\\w", "\\s", "\\b", "[A-Za-z", "[a-z", "[0-9", "(?:", "(?=", "(?<")
    return any(m in body for m in marks)


def main() -> int:
    findings = []
    checked = 0
    for path in sorted(SRC.rglob("*.kt")):
        raw = path.read_text()
        if "Regex(" not in raw and "Pattern.compile" not in raw:
            continue
        checked += 1
        lines = raw.split("\n")
        for line_no, body in literals(raw):
            if not looks_like_a_pattern(body):
                continue
            if not UNBOUNDED_GROUP.search(body):
                continue
            window = "\n".join(lines[max(0, line_no - 7):line_no])
            if OPT_OUT.search(window):
                continue
            findings.append((path.relative_to(ROOT), line_no, body.strip()[:96]))

    if not findings:
        print(
            "regex-recursion: %d files with patterns checked, "
            "no unbounded group repetition." % checked
        )
        return 0
    print("regex-recursion: a repeated group in a regex overflows the stack.")
    print("  java.util.regex recurses once per repetition; two thousand is enough.")
    print("  Use a bounded character class and check the structure in code,")
    print("  as domain/Hostnames.kt does, or mark it `// regex-recursion: <why>`.")
    for rel, line, text in findings:
        print("  %s:%d  %s" % (rel, line, text))
    return 1


if __name__ == "__main__":
    sys.exit(main())
