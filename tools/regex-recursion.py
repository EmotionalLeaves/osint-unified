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


TEMPLATE_HOLE = re.compile(r"\$\{[^{}]{0,200}\}")


def literals(text):
    """Every string literal, with the line it starts on.

    Template holes are taken out: `${utc(it.toLong() * 1000)}` is code
    inside a string, and its `) * 1000` read as a repeated group.
    """
    out = []
    spans = []
    for m in RAW.finditer(text):
        spans.append(m.span())
        out.append((text.count("\n", 0, m.start()) + 1, TEMPLATE_HOLE.sub("", m.group(1))))
    for m in PLAIN.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        out.append((text.count("\n", 0, m.start()) + 1, TEMPLATE_HOLE.sub("", m.group(1))))
    return out


def looks_like_a_pattern(body):
    """A string that is doing regex work rather than holding prose.

    A capturing group with none of these marks is still caught when it
    is the argument of a Regex(...) call — see regex_calls.
    """
    if len(body) < 4:
        return False
    marks = ("\\d", "\\w", "\\s", "\\b", "[A-Za-z", "[a-z", "[0-9", "(?:", "(?=", "(?<", "[^")
    return any(m in body for m in marks)


# A repeated character class followed at once by a character that class
# also matches: `[A-Za-z0-9.\-]+\.` — the email pattern's host half.
# Every split of a run between the class and the literal is a candidate,
# so a long run with no match is tried at each start position over its
# whole length: quadratic, measured at fifty seconds over a 96 KB body.
# This is a different defect from the recursion above and the first
# version of this check could not see it, by construction.
CLASS_THEN = re.compile(r"(?<!\\)\[((?:\\.|[^\]\\]){1,80})\][+*]\s*(\\.|[^\\\[(|)?*+{^$\s])")


def ambiguous_class(body):
    for m in CLASS_THEN.finditer(body):
        cls, nxt = m.group(1), m.group(2)
        # A negated class runs only until its delimiter — `[^>]*` stops
        # at the end of a tag — and in real input that is near. The
        # quadratic shape is a positive class with nothing to stop it,
        # as the email pattern's host half was.
        if cls.startswith("^"):
            continue
        ch = nxt[1:] if nxt.startswith("\\") else nxt
        # An escape that is a class itself (\d, \w, \s) is not a literal.
        if nxt.startswith("\\") and ch in "dDwWsSbBpPnrtfv0123456789":
            continue
        if len(ch) != 1:
            continue
        try:
            if re.fullmatch("[" + cls + "]", ch):
                return m.group(0)
        except re.error:
            continue
    return None


def regex_calls(text):
    """The joined literal text of every Regex(...) argument.

    A pattern assembled from pieces — `"(" + part + ")+"` — splits the
    repetition into a literal too short to look at. Joined, it is whole.
    """
    out = []
    for m in re.finditer(r"\b(?:Regex|Pattern\.compile)\s*\(", text):
        depth, i = 0, m.end() - 1
        while i < len(text):
            c = text[i]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        arg = text[m.end():i]
        joined = "".join(body for _, body in literals(arg))
        out.append((text.count("\n", 0, m.start()) + 1, joined))
    return out


def main() -> int:
    findings = []
    checked = 0
    for path in sorted(SRC.rglob("*.kt")):
        raw = path.read_text()
        checked += 1
        lines = raw.split("\n")
        candidates = [(n, b) for n, b in literals(raw) if looks_like_a_pattern(b)]
        candidates += regex_calls(raw)
        seen = set()
        for line_no, body in candidates:
            why = None
            if UNBOUNDED_GROUP.search(body):
                why = "repeated group"
            else:
                amb = ambiguous_class(body)
                if amb:
                    why = "class then a character it also matches: " + amb
            if not why or (line_no, why) in seen:
                continue
            seen.add((line_no, why))
            window = "\n".join(lines[max(0, line_no - 7):line_no])
            if OPT_OUT.search(window):
                continue
            findings.append((path.relative_to(ROOT), line_no, why, body.strip()[:96]))

    if not findings:
        print(
            "regex-recursion: %d files checked, no unbounded group repetition "
            "and no ambiguous class run." % checked
        )
        return 0
    print("regex-recursion: a regex that can overflow the stack or run quadratically.")
    print("  java.util.regex recurses once per repetition of a group, and a class")
    print("  run followed by a character it also matches backtracks over every split.")
    print("  Bound it, scan in code as domain/Hostnames.kt does, or mark it")
    print("  `// regex-recursion: <why>`.")
    for rel, line, why, text in findings:
        print("  %s:%d  [%s]  %s" % (rel, line, why, text))
    return 1


if __name__ == "__main__":
    sys.exit(main())
