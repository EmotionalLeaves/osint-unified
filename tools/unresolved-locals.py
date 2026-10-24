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
# Parameter lists, scanned separately from declarations.
#
# A parameter is a declaration the DECL pattern cannot see: there is no
# val, var or fun in front of it. That blind spot let `verify(hits)` call
# a function whose body read a `username` it had never been given — the
# name is a parameter of four other functions in the same file, so it is
# obviously a real name, and the check knew nothing about it. Parameter
# names go into the project-wide set so the diagnostic is believed, but
# deliberately NOT into the per-file set: the per-file skip exists for
# delegated properties, and extending it to parameters would hide
# exactly this mistake, which is always made in a file that has the
# name as a parameter somewhere else.
SIGNATURE = re.compile(r"\b(?:fun|class|constructor)\b[^(\n{]*\(")
PARAM = re.compile(
    r"(?:^|,)\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:vararg|noinline|crossinline|private|internal|protected|"
    r"public|override|open|final|lateinit)\s+)*"
    r"(?:val\s+|var\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:"
)


# `{ name ->` and `{ a, b ->`, and `{ (a, b) ->` for a destructure.
LAMBDA_PARAM = re.compile(r"\{\s*([A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*)\s*->")
LAMBDA_DESTRUCTURE = re.compile(r"\{\s*\(([^)]*)\)\s*->")

ENUM = re.compile(r"\benum\s+class\s+[A-Za-z_][A-Za-z0-9_]*[^{\n]*\{")
ENTRY = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*([A-Z_][A-Za-z0-9_]*)")


def enum_entries(text):
    """Every name declared as an entry of an enum in this file.

    An enum entry is a declaration the DECL pattern cannot see either:
    there is no val, var, fun or class in front of it. Adding
    ToolCategory.PERSINT and using it bare in another file without its
    import compiled nowhere and the pre-flight said nothing, because
    the name was invisible to the scan. Unlike parameters these DO go
    into the per-file set as well, since inside the enum's own body an
    entry really is visible bare.
    """
    out = set()
    for m in ENUM.finditer(text):
        i = m.end() - 1
        depth = 0
        for j in range(i, len(text)):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
        else:
            continue
        body = text[i + 1:j]
        # Entries come first and end at the semicolon, if there is one.
        head = body.split(";", 1)[0]
        chunk, depth = [], 0
        parts = []
        for c in head:
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            if c == "," and depth == 0:
                parts.append("".join(chunk))
                chunk = []
            else:
                chunk.append(c)
        parts.append("".join(chunk))
        for part in parts:
            e = ENTRY.match(part)
            if e:
                out.add(e.group(1))
    return out


def parameter_names(text):
    """Every name that appears as a parameter in this file."""
    out = set()
    for m in SIGNATURE.finditer(text):
        i = m.end() - 1
        depth = 0
        for j in range(i, len(text)):
            c = text[j]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
        else:
            continue
        # Only the top level of the list: a default value can itself be
        # a call with named arguments, and those are not parameters here.
        flat, depth = [], 0
        for c in text[i + 1:j]:
            if c in "([{":
                depth += 1
                flat.append(" ")
            elif c in ")]}":
                depth -= 1
                flat.append(" ")
            else:
                flat.append(" " if depth > 0 else c)
        out.update(PARAM.findall("".join(flat)))
    # Lambda parameters too.
    #
    # `index["seed"]?.let { seedIndex -> … }` declares a name that
    # DECL cannot see — there is no val in front of it — and a use
    # of that name OUTSIDE the lambda is then dismissed as the SDK's
    # absence rather than reported. That shipped a red build: the
    # pin was moved into a `let` and the second use of it, forty
    # lines down inside the iteration loop, was left behind. CI
    # found it six minutes later, which is what this file exists to
    # prevent.
    #
    # Split, not added whole: findall returns the one group, so
    # `{ a, b -> }` came back as the single string "a, b" and neither
    # name was ever learned — the fix for rule 8 only covered lambdas
    # with one parameter.
    for group in LAMBDA_PARAM.findall(text):
        out.update(n.strip() for n in group.split(",") if n.strip())
    for m in LAMBDA_DESTRUCTURE.finditer(text):
        out.update(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", m.group(1)))
    # The four other ways a name is declared without val, var or fun in
    # front of it, each the same blind spot as the lambda parameter:
    #   { e: Throwable -> }     a typed lambda parameter
    #   for (x in xs), for ((k, v) in m)
    #   val (lat, lon) = …      a destructuring declaration
    #   when (val x = f())  and  catch (e: IOException)
    for m in LAMBDA_HEAD.finditer(text):
        for part in _top_level_split(m.group(1)):
            name = part.split(":", 1)[0].strip()
            if IDENT.fullmatch(name):
                out.add(name)
    for m in FOR_LOOP.finditer(text):
        out.update(IDENT_ANY.findall(m.group(1) or m.group(2) or ""))
    for m in DESTRUCTURE_DECL.finditer(text):
        for part in _top_level_split(m.group(1)):
            name = part.split(":", 1)[0].strip()
            if IDENT.fullmatch(name):
                out.add(name)
    out.update(WHEN_VAL.findall(text))
    out.update(CATCH_PARAM.findall(text))
    out.discard("_")
    return out


IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
IDENT_ANY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
# `{ name: Type ->`, `{ a: A, b: B ->` — the parameters of a typed lambda.
LAMBDA_HEAD = re.compile(r"\{\s*([A-Za-z_][A-Za-z0-9_]*\s*:[^{}\n]{1,200}?)->")
FOR_LOOP = re.compile(
    r"\bfor\s*\(\s*(?:\(([^)]{1,120})\)|([A-Za-z_][A-Za-z0-9_]*))\s*(?::[^)]{1,80}?)?\s+in\b"
)
DESTRUCTURE_DECL = re.compile(r"\b(?:val|var)\s*\(([^)]{1,200})\)\s*(?::[^=]{1,80})?=")
WHEN_VAL = re.compile(r"\bwhen\s*\(\s*val\s+([A-Za-z_][A-Za-z0-9_]*)")
CATCH_PARAM = re.compile(r"\bcatch\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*:")


def _top_level_split(s):
    """Split on commas that are not inside <>, () or []."""
    parts, depth, cur = [], 0, []
    for c in s:
        if c in "<([":
            depth += 1
        elif c in ">)]":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    parts.append("".join(cur))
    return parts
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
    params = set()
    per_file = {}
    declared_in = {}
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                full = os.path.join(dirpath, f)
                imported = set()
                here: set = set()
                with open(full, encoding="utf-8", errors="replace") as fh:
                    body = fh.read()
                params.update(parameter_names(body))
                entries = enum_entries(body)
                names.update(entries)
                here.update(entries)
                for line in body.splitlines():
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
    return names | params, params - names, per_file, declared_in


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    declared, param_only, imports_by_file, declared_by_file = scan([
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
    param_only -= ignore

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
        # A name known only as a parameter, being assigned to.
        #
        # Kotlin parameters cannot be reassigned, so an assignment to a
        # bare name is never the mistake this check hunts — it is a
        # property of an implicit receiver the SDK supplies, the `style`
        # inside `Paint().apply { ... }`. Skipping these is what makes
        # parameter names usable as evidence at all, and it costs
        # nothing: there is no bug on the other side of it.
        if name in param_only and all(
            re.match(r"\s*=(?![=>])", whole[at + len(name):])
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
