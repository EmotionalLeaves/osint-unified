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
# Both spellings on purpose.
#
# Kotlin 1.9 wrote "unresolved reference: Place" and Kotlin 2.0 writes
# "unresolved reference 'Place'." — and this pattern only knew the first
# one, so from the day the pre-flight moved to kotlinc 2.0.21 it matched
# nothing at all. The check reported "no problems" for every run and the
# errors it exists to catch went to CI instead, which is the one outcome
# it was written to prevent. A check that cannot fail is worse than no
# check, because it is also a claim.
DIAG = re.compile(
    r"^(?:e: )?(?:file://)?(\S+\.kt):(\d+):(\d+): error: "
    r"[Uu]nresolved reference(?::\s+|\s+')([A-Za-z_][A-Za-z0-9_]*)"
)


def scan(roots):
    """Names this project declares, and what each file imports.

    Imports are tracked per file, not pooled. A name imported in one file
    and used bare in another *without* its import is precisely the mistake
    worth catching — pooling them hides it, which is how four missing
    imports reached CI. An extension receiver reads like a declaration
    ("fun JsonObject?.str"), so the declaration pattern refuses a name
    followed by a dot.
    """
    names = set()
    per_file = {}
    declared_in = {}
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                full = os.path.join(dirpath, f)
                imported = set()
                here = set()
                with open(full, encoding="utf-8", errors="replace") as fh:
                    for line in fh:
                        m = IMPORTED.match(line)
                        if m:
                            imported.add(m.group(2) or m.group(1))
                            continue
                        m = DECL.match(line)
                        if m:
                            names.add(m.group(1))
                            here.add(m.group(1))
                per_file[os.path.realpath(full)] = imported
                declared_in[os.path.realpath(full)] = here
    return names, per_file, declared_in


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    declared, imports_by_file, declared_by_file = scan([
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
        # Imported into THIS file, so the name here is the library's.
        if name in imports_by_file.get(os.path.realpath(path), ()):  # noqa: SIM118
            continue
        if path not in sources:
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    sources[path] = fh.read().splitlines()
            except OSError:
                sources[path] = []
        src = sources[path]
        if lineno - 1 >= len(src):
            continue
        whole = src[lineno - 1]
        # An import of a library the classpath does not have reports its
        # first path segment as an unresolved bare name — `net` in
        # `import net.zetetic...`. That is the missing SDK again, and the
        # import line is the one place a bare name is never a mistake we
        # could make.
        if whole.lstrip().startswith("import "):
            continue
        # Declared in this very file, so it is a local or a property
        # here and the compiler is complaining about something else —
        # most often a delegated property whose delegate needs the SDK
        # ("val emit by rememberUpdatedState(...)"), which makes every
        # later read of the name unresolved through no fault of ours.
        if name in declared_by_file.get(os.path.realpath(path), ()):
            continue
        # A dotted access is unresolved because its receiver was, which is
        # the SDK's absence rather than ours. Only bare names count.
        #
        # The column cannot be trusted to land on the name. K2 points at
        # the enclosing expression for some diagnostics — "it.unseen"
        # inside a lambda is reported at the brace — so when the name is
        # not actually at the column, every occurrence of it on the line
        # is examined instead, and the diagnostic only counts if at
        # least one of them is bare.
        if whole[col - 1:col - 1 + len(name)] == name:
            positions = [col - 1]
        else:
            positions = [
                m.start() for m in
                re.finditer(r"\b" + re.escape(name) + r"\b", whole)
            ]
            if not positions:
                continue
        if all(
            whole[:at].rstrip().endswith((".", "?", "::"))
            for at in positions
        ):
            continue
        col = positions[0] + 1
        hits.append("{}:{}:{} {} — declared in this project but not visible here"
                    .format(os.path.relpath(path, root), lineno, col, name))

    for h in sorted(set(hits)):
        print(h)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
