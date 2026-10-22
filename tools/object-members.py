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

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"

OBJECT = re.compile(r"^(\s*)(?:private\s+|internal\s+)?object\s+([A-Z][A-Za-z0-9_]*)\s*\{", re.M)
MEMBER = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:private\s+|internal\s+|public\s+|const\s+|override\s+|lateinit\s+|inline\s+"
    r"|data\s+|sealed\s+|value\s+|abstract\s+|open\s+|enum\s+|annotation\s+|suspend\s+)*"
    r"(?:val|var|fun|object|class|interface|typealias)\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)",
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
    whole = "\n".join(p.read_text(errors="replace") for p in files)

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

    bad: list[str] = []
    for path in files:
        text = path.read_text(errors="replace")
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

    if bad:
        print("Members named on a project object that it does not declare:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(f"object-members: {len(objects)} singletons checked, all members resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
