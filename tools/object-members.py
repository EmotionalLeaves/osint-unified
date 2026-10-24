#!/usr/bin/env python3
"""Members named on a project singleton that the singleton does not have.

`Intel.Green` compiles the same way `Intel.Cyan` does, right up until the
Android build resolves it — and the local check cannot, because it treats
every unresolved name as missing-SDK noise. But a plain `object Foo { ... }`
that inherits from nothing is fully knowable from the source: its members
are exactly what is written inside it.

So for every such object, every `Foo.member` written anywhere in the project
is checked against what Foo declares. Objects with a supertype are skipped —
their members can come from somewhere this script cannot see.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"


def code(path: Path) -> str:
    """A file with its comments and string contents blanked out.

    Everything below asks "is this dotted name written anywhere?",
    and prose answers yes. A doc comment explaining that
    `AreaReport.LAYER_WORDS` used to exist and was deleted is a
    sentence about the past, not a call — but it was read as one,
    and the check failed on the commit that removed the member and
    said so. The same false positive fired once before on a comment
    naming a file. Offsets and newlines survive the blanking, so
    reported line numbers stay true.
    """
    return without_prose(path.read_text(errors="replace"))

OBJECT = re.compile(r"^(\s*)(?:private\s+|internal\s+)?object\s+([A-Z][A-Za-z0-9_]*)\s*\{", re.M)
MEMBER = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:private\s+|internal\s+|public\s+|const\s+|override\s+|lateinit\s+|inline\s+"
    r"|data\s+|sealed\s+|value\s+|abstract\s+|open\s+|enum\s+|annotation\s+|suspend\s+)*"
    r"(?:val|var|fun|object|class|interface|typealias)\s+(?:<[^>]*>\s*)?"
    # An extension member declared inside the object — `fun
    # ResponseBody.bodyText()` — is named bodyText, not
    # ResponseBody. Capturing the first identifier recorded it
    # under the receiver's name, so every use of the real name was
    # reported as a member that does not exist.
    r"(?:[A-Za-z_][A-Za-z0-9_]*(?:<[^>]*>)?\.)?([A-Za-z_][A-Za-z0-9_]*)",
    re.M,
)


def body_of(text: str, open_brace: int) -> str:
    """Source between the object's braces, counting nesting."""
    depth = 0
    for i in range(open_brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1:i]
    return ""


def main() -> int:
    files = list(SRC.rglob("*.kt"))
    whole = "\n".join(code(p) for p in files)

    objects: dict[str, set[str]] = {}
    for m in OBJECT.finditer(whole):
        name = m.group(2)
        body = body_of(whole, m.end() - 1)
        members = set(MEMBER.findall(body))
        # Enum-style constant lists and `Name to value` maps are not
        # declarations, so an object whose members are generated some other
        # way would look empty; those are not worth guessing about.
        if not members:
            continue
        objects.setdefault(name, set()).update(members)

    # Any object that extends something could have inherited members.
    for m in re.finditer(r"\bobject\s+([A-Z][A-Za-z0-9_]*)\s*:", whole):
        objects.pop(m.group(1), None)

    # A `private companion object` is invisible from any other file, so a
    # constant declared in one and read from another compiles here and
    # fails in CI with "Cannot access 'Companion'" — which is how r178
    # died, over a field name the sweep and its classifier both had to
    # know.
    private_companions: dict[str, tuple[set[str], Path]] = {}
    for path in files:
        text = code(path)
        for m in re.finditer(
            r"\bclass\s+([A-Z][A-Za-z0-9_]*)[\s\S]{0,20000}?"
            r"^\s*private\s+companion\s+object\s*\{", text, re.M
        ):
            brace = text.index("{", m.end() - 1)
            members = set(MEMBER.findall(body_of(text, brace)))
            if members:
                private_companions[m.group(1)] = (members, path)

    bad: list[str] = []
    for path in files:
        text = code(path)
        for owner, (members, home) in private_companions.items():
            if path == home:
                continue
            for use in re.finditer(r"\b%s\.([A-Za-z_][A-Za-z0-9_]*)" % re.escape(owner), text):
                if use.group(1) not in members:
                    continue
                line = text.count("\n", 0, use.start()) + 1
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: {owner}.{use.group(1)} — "
                    f"{owner}'s companion object is private, so this is not "
                    f"visible outside {home.name}"
                )

    for path in files:
        text = code(path)
        for name, members in objects.items():
            for use in re.finditer(r"\b%s\.([a-zA-Z_][A-Za-z0-9_]*)" % re.escape(name), text):
                member = use.group(1)
                if member in members:
                    continue
                # Kotlin gives every object these, and a companion's owner
                # may also be referenced through it.
                if member in {
                    "javaClass", "toString", "hashCode", "equals", "apply",
                    "also", "let", "run", "with", "takeIf", "takeUnless",
                    "Companion", "INSTANCE", "entries", "values", "valueOf",
                }:
                    continue
                line = text.count("\n", 0, use.start()) + 1
                bad.append(f"{path.relative_to(ROOT)}:{line}: {name}.{member} — {name} has no such member")

    # A visible companion member whose type is private to the class. Kotlin
    # refuses "'public' property exposes its 'private-in-class' type", and
    # it only became reachable when the companion stopped being private —
    # which is how r179 died, one commit after r178 died making it visible.
    for path in files:
        text = code(path)
        private_types = set(
            re.findall(
                r"\bprivate\s+(?:data\s+|sealed\s+|value\s+|inner\s+|enum\s+)*"
                r"(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)",
                text,
            )
        )
        if not private_types:
            continue
        for m in re.finditer(r"^(\s*)(?!.*\bprivate\b)companion\s+object\b", text, re.M):
            brace = text.find("{", m.end())
            if brace < 0:
                continue
            body = body_of(text, brace)
            offset = brace + 1
            for decl in re.finditer(
                r"^\s*(?:@\w+\s+)*(?!.*\bprivate\b)(?:const\s+|@Volatile\s+)*"
                r"(?:val|var|fun)\s+[A-Za-z_][A-Za-z0-9_]*\s*(?::|\()([^=\n]*)",
                body, re.M
            ):
                mentioned = [t for t in private_types if re.search(r"\b%s\b" % t, decl.group(1))]
                if not mentioned:
                    continue
                line = text.count("\n", 0, offset + decl.start()) + 1
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: a visible companion member "
                    f"has the private type {mentioned[0]} in its signature — "
                    f"Kotlin refuses that"
                )

    # A top-level `private fun` called from another file.
    #
    # Kotlin scopes a file-private declaration to its file, and the
    # local check cannot see the difference: `jsonObjectOrNull()` is
    # declared private in PeopleApis.kt, was called from DeepApis.kt,
    # compiled here and died in CI with "Unresolved reference". That
    # is r512, and it is the same shape as the private companion
    # above — the pre-flight asks whether a name exists in the
    # project, not whether it is in scope where it is written.
    # Every file that declares it privately, not just the first.
    #
    # Two classes may each have their own `private fun shape()` and
    # each call it at home, which is correct; flagging the second
    # one because the first was recorded would be a false positive
    # in a check whose whole value is that it is trusted.
    file_private: dict[str, set[Path]] = {}
    public_names: set[str] = set()
    PRIVATE_DECL = re.compile(
        r"(?:^|\n)[ \t]*private\s+(?:suspend\s+|inline\s+|tailrec\s+|operator\s+|infix\s+)*"
        r"fun\s+(?:<[^>]*>\s*)?"
        r"(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\("
    )
    # Anything declared without `private` in front of it, at any
    # indent, could be what a call site is reaching.
    OPEN_DECL = re.compile(
        r"(?:^|\n)[ \t]*(?!private\b)(?:[a-z]+\s+)*"
        r"fun\s+(?:<[^>]*>\s*)?"
        r"(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\("
    )
    # A type name is a constructor call, and `Entry(...)` on a data
    # class reads exactly like a function call to this check. So every
    # declared type is a callable name, whatever its visibility — a
    # private data class used inside its own file is fine, and the
    # same-file guard below covers it. Without this, WorldBrief's own
    # `data class Entry` was reported because MoreScreen happens to
    # have a `private fun Entry`.
    TYPE_DECL = re.compile(
        r"(?:^|\n)[ \t]*(?:[a-z]+\s+)*"
        r"(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)"
    )
    # Blanked once per file, not once per file per name: the inner
    # loop below is over every private name, and recomputing the
    # prose-blanking each time turned a quarter-second check into a
    # two-minute one.
    blanked = {path: code(path) for path in files}
    # What each file imports: the leaf names it brought in explicitly,
    # and whether it has a wildcard import.
    #
    # A private fun in one file and an imported library function in
    # another can share a name — `launch` is a coroutine builder and
    # also a `private fun launch` in a test — and the call resolves to
    # the import, not the project's private one. Without this the check
    # reported kotlinx.coroutines.launch as out of scope. A wildcard
    # import could supply anything, so a file that has one is left
    # alone rather than guessed about: precision matters more than
    # recall in a check that is only worth having if it is trusted.
    imported: dict[Path, set[str]] = {}
    wildcard: set[Path] = set()
    IMPORT = re.compile(r"(?m)^\s*import\s+([A-Za-z0-9_.]+)(\.\*)?")
    for path, text in blanked.items():
        names: set[str] = set()
        for m in IMPORT.finditer(text):
            if m.group(2):
                wildcard.add(path)
            else:
                names.add(m.group(1).rsplit(".", 1)[-1])
        imported[path] = names
    for path, text in blanked.items():
        for m in PRIVATE_DECL.finditer(text):
            file_private.setdefault(m.group(1), set()).add(path)
        for m in OPEN_DECL.finditer(text):
            public_names.add(m.group(1))
        for m in TYPE_DECL.finditer(text):
            public_names.add(m.group(1))
    for name, homes in file_private.items():
        if name in public_names:
            continue
        # Nothing outside the declaring files even mentions the
        # name: the common case, settled with one substring test
        # before any regex runs.
        needle = name + "("
        for path, text in blanked.items():
            if path in homes or needle not in text.replace(" ", ""):
                continue
            # Resolved by an import here, not by the project's
            # private declaration.
            if name in imported[path] or path in wildcard:
                continue
            # Or declared in this file as something callable that is not a
            # fun: a function-typed property or parameter. BundledPoints
            # takes `private val label: (JsonObject) -> String` and calls
            # `label(o)`, which is its own value and nothing to do with a
            # `private fun label` in ConflictDataSource.
            if re.search(r"\b(?:val|var)\s+%s\b|\b%s\s*:\s*(?:suspend\s*)?\(" % (
                re.escape(name), re.escape(name)
            ), text):
                continue
            for use in re.finditer(r"(?<![A-Za-z0-9_.])%s\s*\(" % re.escape(name), text):
                line = text.count("\n", 0, use.start()) + 1
                where = ", ".join(sorted(h.name for h in homes))
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: {name}() is private to "
                    f"{where}, so it is not in scope here"
                )

    if bad:
        print("Members that will not resolve where they are used:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(
        f"object-members: {len(objects)} singletons and "
        f"{len(private_companions)} private companions checked, all members resolve."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
