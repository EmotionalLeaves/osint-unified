#!/usr/bin/env python3
"""
Catch the one unresolved reference that is actually our fault.

Without the Android SDK, kotlinc cannot resolve several thousand symbols,
so "unresolved reference" is normally noise. But a *bare* identifier —
no receiver before it — that is nevertheless declared somewhere in this
project is a different thing: it means the name exists, and we are using
it where it is not visible. That is a real error, it compiles nowhere,
and CI is otherwise the only thing that finds it, six minutes later.

Reads kotlinc output on stdin or from a file argument; prints offenders.
"""
import os
import re
import sys

DECL = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public |private |internal |protected |"
    r"const |lateinit |override |open |abstract |final |inline |"
    r"suspend |external |expect |actual |companion |data |sealed |enum |"
    r"annotation |value |tailrec |operator |infix )*"
    r"(?:val|var|fun|class|object|interface|typealias)\s+"
    r"(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_.?])"
)
IMPORTED = re.compile(r"^\s*import\s+[\w.]*?\.([A-Za-z_][A-Za-z0-9_]*)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$")
DIAG = re.compile(r"^(?:e: )?(?:file://)?(\S+\.kt):(\d+):(\d+): error: unresolved reference: ([A-Za-z_][A-Za-z0-9_]*)")


def project_declarations(roots):
    """Names this project declares, minus anything it also imports.

    An extension receiver reads like a declaration ("fun JsonObject?.str"),
    and a name the project imports from a library is that library's, not
    ours — both would otherwise be reported for every file that lacks the
    SDK. Excluding imported names costs nothing real: a project symbol
    does not need importing to be used where it belongs.
    """
    names = set()
    imported = set()
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                with open(os.path.join(dirpath, f), encoding="utf-8", errors="replace") as fh:
                    for line in fh:
                        m = IMPORTED.match(line)
                        if m:
                            imported.add(m.group(2) or m.group(1))
                            continue
                        m = DECL.match(line)
                        if m:
                            names.add(m.group(1))
    return names - imported


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    declared = project_declarations([
        os.path.join(root, "app/src/main/java"),
        os.path.join(root, "app/src/test/java"),
    ])

    ignore = set()
    ignore_path = os.path.join(root, "tools/unresolved-ignore.txt")
    if os.path.exists(ignore_path):
        with open(ignore_path, encoding="utf-8") as fh:
            ignore = {
                ln.strip() for ln in fh
                if ln.strip() and not ln.startswith("#")
            }
    declared -= ignore

    text = open(sys.argv[1], encoding="utf-8", errors="replace") if len(sys.argv) > 1 else sys.stdin
    sources = {}
    hits = []
    for line in text:
        m = DIAG.match(line.strip())
        if not m:
            continue
        path, lineno, col, name = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        if name not in declared:
            continue
        if not os.path.isabs(path):
            path = os.path.join(root, path)
        if path not in sources:
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    sources[path] = fh.read().splitlines()
            except OSError:
                sources[path] = []
        src = sources[path]
        if lineno - 1 >= len(src):
            continue
        before = src[lineno - 1][: col - 1].rstrip()
        # A dotted access is unresolved because its receiver was, which is
        # the SDK's absence rather than ours. Only bare names count.
        if before.endswith(".") or before.endswith("?") or before.endswith("::"):
            continue
        hits.append("{}:{}:{} {} — declared in this project but not visible here"
                    .format(os.path.relpath(path, root), lineno, col, name))

    for h in sorted(set(hits)):
        print(h)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
