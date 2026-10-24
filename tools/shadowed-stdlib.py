#!/usr/bin/env python3
"""Stdlib type names this project also declares, used as the stdlib one.

`Result.failure(...)` is unremarkable Kotlin — until the file sits in a
package that declares its own `Result`, which this one does: the Room
entity for a tool's output. The project's name wins, `Result.failure`
resolves to a data class that has no such member, and the build dies
with "Unresolved reference 'failure'". It cost r412.

The local syntax check could not see it: without the Android SDK the
surrounding declarations are already unresolved, and the real error
arrives looking like more of the same noise. So it is checked here
instead, from the source alone, which is enough: a top-level
declaration's package is written at the top of its file, and so is
every use.

For each stdlib name the project shadows, every use of
`Name.<companionMember>` inside that same package is reported, unless
the file explicitly imports the stdlib one.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"

# Members that only exist on the stdlib type, so naming one is proof
# the stdlib type was meant.
STDLIB_MEMBERS = {
    "Result": {"failure", "success"},
    "Pair": {"copy"},
    "Error": {"javaClass"},
    "Exception": {"javaClass"},
}

PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)


DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+"
    r"|data\s+|value\s+|annotation\s+|enum\s+)*"
    r"(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)


def main() -> int:
    files = list(SRC.rglob("*.kt"))
    # package -> {shadowed stdlib name -> the file that declares it}
    shadowed: dict[str, dict[str, Path]] = {}
    texts: dict[Path, str] = {}
    for path in files:
        text = path.read_text(errors="replace")
        texts[path] = without_prose(text)
        pkg = PACKAGE.search(text)
        if not pkg:
            continue
        for name in DECL.findall(text):
            if name in STDLIB_MEMBERS:
                shadowed.setdefault(pkg.group(1), {})[name] = path

    if not shadowed:
        print("shadowed-stdlib: the project shadows no stdlib type name.")
        return 0

    bad: list[str] = []
    for path in files:
        text = texts[path]
        pkg = PACKAGE.search(text)
        if not pkg:
            continue
        here = shadowed.get(pkg.group(1))
        if not here:
            continue
        for name, home in here.items():
            if re.search(r"^import\s+kotlin\.%s\b" % re.escape(name), text, re.M):
                continue
            for use in re.finditer(
                r"\b%s\.([A-Za-z_][A-Za-z0-9_]*)" % re.escape(name), text
            ):
                if use.group(1) not in STDLIB_MEMBERS[name]:
                    continue
                line = text.count("\n", 0, use.start()) + 1
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: {name}.{use.group(1)} — "
                    f"this package declares its own {name} "
                    f"({home.name}), which wins over kotlin.{name}"
                )

    if bad:
        print("Stdlib members named on a shadowed type:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    names = sorted({n for pkg in shadowed.values() for n in pkg})
    print(
        f"shadowed-stdlib: {', '.join(names)} shadowed, "
        f"no stdlib member named on them."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
