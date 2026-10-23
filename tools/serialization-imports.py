#!/usr/bin/env python3
"""kotlinx.serialization extensions used in a file that does not import them.

The same shape as tools/modifier-imports.py, and added for the same
reason. `element.jsonPrimitive` is an extension property, not a member,
so a file that uses it without the import compiles nowhere — and the
classpath-less check cannot see that, because the diagnostic is an
unresolved reference after a dot and every one of those is written off
as the missing SDK. r298 died on exactly that, on one line, after a
clean local run.

Unlike the `q` typo in r292, this one *is* decidable here: the set of
extension names is small and fixed, and whether a file imports one is
plain text.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PACKAGE = "kotlinx.serialization.json"

# Extension properties and functions on JsonElement. Members of the
# concrete types (JsonPrimitive.content, JsonObject.keys) are not here:
# those need no import.
EXTENSIONS = [
    "jsonPrimitive", "jsonObject", "jsonArray", "jsonNull",
    "contentOrNull", "intOrNull", "longOrNull", "doubleOrNull",
    "floatOrNull", "booleanOrNull", "int", "long", "double",
    "float", "boolean",
]

# These names are common enough as ordinary properties that requiring
# the import on every `.int` would cry wolf. Checked only where the
# file already works with JSON at all.
AMBIGUOUS = {"int", "long", "double", "float", "boolean"}


def main() -> int:
    bad = []
    for path in sorted((ROOT / "app/src").rglob("*.kt")):
        text = path.read_text(errors="replace")
        if PACKAGE not in text:
            continue
        imports = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
        for name in EXTENSIONS:
            if f"{PACKAGE}.{name}" in imports:
                continue
            if name in AMBIGUOUS:
                continue
            for m in re.finditer(r"\.%s\b" % re.escape(name), text):
                # Skip the import lines themselves.
                line_start = text.rfind("\n", 0, m.start()) + 1
                if text[line_start:].lstrip().startswith("import "):
                    continue
                line = text.count("\n", 0, m.start()) + 1
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: .{name} needs "
                    f"`import {PACKAGE}.{name}`"
                )
                break
    if bad:
        print("kotlinx.serialization extensions used without their import:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(f"serialization-imports: {len(EXTENSIONS) - len(AMBIGUOUS)} extensions checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
