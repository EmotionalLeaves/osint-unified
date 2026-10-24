#!/usr/bin/env python3
"""rememberSaveable given an identity as an input rather than as a key.

`rememberSaveable(case.id) { ... }` reads like "one of these per case".
It is not. The first vararg is `inputs` — it only decides when to
RESET the value — while the registry key stays the positional
composite hash. Inside a `forEach` over several items, every iteration
has the same composite hash, so saved values are restored
POSITIONALLY: arm a destructive button on the third row, rotate while
the list has shifted, and the armed state lands on a different row.

That is H20 in this project's backlog, and the reason it survived
review is that the call site reads correctly.

So: any `rememberSaveable(<something that looks like an identity>)`
with no `key =` is reported. An identity is a `.id`, an `.id` through
a receiver, or a bare name ending in `Id`. Passing the same value
again as `key = ...` is what the call site almost always meant.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main"

CALL = re.compile(r"\brememberSaveable\s*\(([^)]*)\)")
IDENTITY = re.compile(r"(^|\.)\w*[Ii]d$|\.id$|^\w+\.id$")


def main() -> int:
    bad: list[str] = []
    for path in sorted(SRC.rglob("*.kt")):
        text = without_prose(path.read_text(errors="replace"))
        for m in CALL.finditer(text):
            args = m.group(1).strip()
            if not args or "key" in args:
                continue
            first = args.split(",")[0].strip()
            if not IDENTITY.search(first):
                continue
            line = text.count("\n", 0, m.start()) + 1
            bad.append(
                f"{path.relative_to(ROOT)}:{line}: rememberSaveable({first}) — "
                f"that is an input, not the saved-state key. Inside a loop "
                f"every iteration shares one key and values restore "
                f"positionally; pass key = {first}.toString() as well"
            )
    if bad:
        print("Identities passed to rememberSaveable as inputs:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print("saveable-keys: no identity is being used as an input where a key was meant.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
