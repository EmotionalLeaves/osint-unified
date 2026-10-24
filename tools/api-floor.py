#!/usr/bin/env python3
"""Java and Android APIs newer than this app's minSdk.

`InputStream.readNBytes` compiles, passes every check here, and throws
NoSuchMethodError on a phone running anything below Android 13. The
local check cannot see it — it has no SDK — and CI compiles against
compileSdk, where the method exists. Lint would catch it; lint is not
what gates this build.

So the handful that are easy to reach for and wrong below the floor
are listed here by name. The list is short on purpose: it holds the
ones that look like ordinary Kotlin or ordinary Java and are not, which
is the only kind that gets written by accident.

Adding to it costs one line. Leaving something out costs a crash on
somebody's phone, so when in doubt, add it.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main"
GRADLE = ROOT / "app/build.gradle.kts"

# Member call -> the API level that introduced it on Android.
TOO_NEW = {
    "readNBytes": 33,
    "readAllBytes": 33,
    "transferTo": 33,
    "writeString": 33,
    "readString": 33,
    "toUnmodifiableList": 33,
    "toUnmodifiableSet": 33,
    "toUnmodifiableMap": 33,
    "strip": 35,
    "stripLeading": 35,
    "stripTrailing": 35,
    "isBlank": None,          # String.isBlank is API 35; Kotlin's own is fine
    "repeat": None,           # same shape
    "chars": 24,
    "takeWhile": None,
    "dropWhile": None,
    "ofEntries": 33,
    "copyOf": None,
    "toArray": None,
}

# Entries whose level is None are ambiguous with a Kotlin stdlib
# function of the same name, so they are not checked: the cost of a
# false positive on `isBlank()` would be the whole project.
CHECKED = {name: level for name, level in TOO_NEW.items() if level is not None}


def min_sdk() -> int:
    text = GRADLE.read_text(errors="replace")
    m = re.search(r"minSdk\s*=\s*(\d+)", text)
    return int(m.group(1)) if m else 0


def main() -> int:
    floor = min_sdk()
    if floor == 0:
        print("api-floor: could not read minSdk, skipping.")
        return 0
    bad: list[str] = []
    for path in sorted(SRC.rglob("*.kt")):
        text = without_prose(path.read_text(errors="replace"))
        for name, level in CHECKED.items():
            if level <= floor:
                continue
            for m in re.finditer(r"\.%s\s*\(" % re.escape(name), text):
                line = text.count("\n", 0, m.start()) + 1
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: .{name}() needs API "
                    f"{level}; this app's floor is {floor}, so it compiles "
                    f"and throws NoSuchMethodError on most phones"
                )
    if bad:
        print("Calls that are newer than minSdk:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(f"api-floor: minSdk {floor}, no call above it among the {len(CHECKED)} checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
