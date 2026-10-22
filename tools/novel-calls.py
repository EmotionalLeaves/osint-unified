#!/usr/bin/env python3
"""Names this change calls that exist nowhere else.

The local check has no Android classpath, so `.foo(` on a library type is
indistinguishable from `.foo(` on a helper that was never written. Two
builds have now failed on exactly that: a helper used at eight call sites
and declared at none.

What separates the two is novelty. A library member the project already
relies on appears in files this change did not touch; a helper that does
not exist appears only in the lines just added. So: every member call
introduced by the working tree, whose name occurs nowhere outside the
added lines and is declared nowhere in the project, is printed for
eyeballing. First uses of a real library API land here too — there are
usually one or two, and they take a second to wave through.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
CALL = re.compile(r"\.([a-z][A-Za-z0-9_]*)\s*\(")
DECL = re.compile(r"\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?([a-z][A-Za-z0-9_]*)\b")


def added_lines() -> list[str]:
    lines = []
    diff = subprocess.run(
        ["git", "diff", BASE, "--unified=0", "--", "*.kt"],
        cwd=ROOT, capture_output=True, text=True,
    ).stdout
    for line in diff.splitlines():
        if line.startswith("+") and not line.startswith("+++"):
            lines.append(line[1:])
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "*.kt"],
        cwd=ROOT, capture_output=True, text=True,
    ).stdout.split()
    for rel in untracked:
        lines.extend((ROOT / rel).read_text(errors="replace").splitlines())
    return lines


def main() -> int:
    added = added_lines()
    if not added:
        print("novel-calls: nothing added.")
        return 0

    sources = list((ROOT / "app/src").rglob("*.kt"))
    whole = "\n".join(p.read_text(errors="replace") for p in sources)
    declared = set(DECL.findall(whole))

    added_text = "\n".join(added)
    suspects = {}
    for name in set(CALL.findall(added_text)):
        if name in declared:
            continue
        # Counting occurrences rather than files: a name used only in the
        # lines just written has the same count in both.
        everywhere = len(re.findall(r"\.%s\s*\(" % re.escape(name), whole))
        here = len(re.findall(r"\.%s\s*\(" % re.escape(name), added_text))
        if everywhere <= here:
            suspects[name] = here

    if not suspects:
        print("novel-calls: no first-use calls in this change.")
        return 0
    print("novel-calls: called here and nowhere else, declared nowhere —")
    for name, count in sorted(suspects.items(), key=lambda kv: -kv[1]):
        print(f"  .{name}()  x{count}")
    print("Each is either a new library API (fine) or a helper never written.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
