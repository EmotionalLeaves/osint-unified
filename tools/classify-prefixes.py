#!/usr/bin/env python3
"""
Every prefix the classifier matches has to be one a tool writes.

`classify` decides whether a source found something, and it does
it by matching the opening of the tool's own output. When the two
drift — a message reworded, a verdict moved to the second line —
the branch becomes dead and that tool silently falls back to
reading English prose, which is the thing classify exists to
replace. It has happened twice.

So: pull every literal out of the `starts(...)` and
`head.contains(...)` calls inside classify, and check each one
appears as a literal somewhere in the files that produce tool
output. A prefix nothing can produce is reported.

It cannot prove the reverse — that every output a tool can write
is covered — because that needs to know which branch produces
which string. It catches the drift, which is the failure that
actually happens.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRODUCERS = [
    "app/src/main/java/com/osint/unified/domain/executor",
    "app/src/main/java/com/osint/unified/data",
    "app/src/main/java/com/osint/unified/domain",
]
CLASSIFY_FILE = (
    "app/src/main/java/com/osint/unified/domain/executor/Executors.kt"
)
# A literal long enough to be a real prefix. Shorter ones are
# fragments like "No " that match everywhere and prove nothing.
MIN_LEN = 12


def classify_body(text: str) -> str:
    i = text.find("override fun classify(")
    if i < 0:
        return ""
    depth = 0
    started = False
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
            started = True
        elif text[j] == "}":
            depth -= 1
            if started and depth == 0:
                return text[i:j + 1]
    return text[i:]


def strip_comments(text: str) -> str:
    out = []
    for line in text.split("\n"):
        in_str = False
        cut = None
        i = 0
        while i < len(line):
            c = line[i]
            if c == "\\" and in_str:
                i += 2
                continue
            if c == '"':
                in_str = not in_str
            elif not in_str and c == "/" and i + 1 < len(line) and line[i + 1] == "/":
                cut = i
                break
            i += 1
        out.append(line if cut is None else line[:cut])
    return "\n".join(out)


def main():
    src = (ROOT / CLASSIFY_FILE).read_text(encoding="utf-8")
    body = classify_body(src)
    if not body:
        print("classify-prefixes: no classify() found — check the anchor.")
        return 1

    # Comments in here quote the strings they are explaining, and a
    # quoted phrase in a comment is not a branch. Stripped before
    # anything is extracted, tracking quote state so a // inside a
    # string literal survives.
    # The ORIGINAL body is what gets cut out of the haystack below;
    # a stripped copy is what literals are read from. Stripping in
    # place made the cut miss and every prefix matched the branch
    # that wrote it again.
    whole = body
    body = strip_comments(body)

    wanted = set()
    for call in re.finditer(r"\bstarts\s*\(([\s\S]*?)\)\s*->", body):
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', call.group(1)):
            if len(lit) >= MIN_LEN:
                wanted.add(lit)
    for lit in re.findall(r'head\.contains\(\s*"((?:[^"\\]|\\.)*)"', body):
        if len(lit) >= MIN_LEN:
            wanted.add(lit)
    for lit in re.findall(r'output\.contains\(\s*"((?:[^"\\]|\\.)*)"', body):
        if len(lit) >= MIN_LEN:
            wanted.add(lit)

    haystack = []
    classify_path = (ROOT / CLASSIFY_FILE).resolve()
    for d in PRODUCERS:
        for p in (ROOT / d).rglob("*.kt"):
            text = p.read_text(encoding="utf-8")
            # The file holding classify() is also a file that
            # produces output, so without this every prefix matches
            # the branch that wrote it and the check passes for
            # ever. It is the whole point of the check that a
            # literal has to exist somewhere OTHER than in the
            # comparison itself.
            if p.resolve() == classify_path:
                text = text.replace(whole, "")
            haystack.append(text)
    joined = "\n".join(haystack)
    # Kotlin splits a long sentence across concatenated literals and
    # across append() calls, so the text a tool actually writes is
    # not contiguous in the source. Glue those joins back together
    # before looking, rather than shortening the thing being looked
    # for — a prefix search that gives up a word at a time will
    # always find something and prove nothing.
    joined = re.sub(r'"\s*\+\s*\n?\s*"', "", joined)
    joined = re.sub(r'"\s*\)\s*\n\s*(?:sb\.)?append\(\s*"', "", joined)

    dead = [lit for lit in sorted(wanted) if lit not in joined]

    if dead:
        print("Prefixes classify() matches that no tool writes:")
        for lit in dead:
            print(f"  {lit!r}")
        print(
            f"\n{len(dead)} dead branch(es). Each one means that tool falls "
            "back to the prose reader without saying so."
        )
        return 1
    print(
        f"classify-prefixes: {len(wanted)} prefixes checked, every one is "
        "written somewhere."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
