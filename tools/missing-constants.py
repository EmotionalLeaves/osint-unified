#!/usr/bin/env python3
"""A SCREAMING_SNAKE name used and declared nowhere in the project.

An edit that adds a use of a constant and then fails before adding the
constant leaves source that reads perfectly and does not compile:
"Unresolved reference 'KEY_SEARCH_COSTS'". It cost r435.

The local syntax check cannot see it. Without the Android SDK, a Kotlin
file is full of unresolved names already, and the real one arrives
looking like more of the same.

But a constant in this style is declared somewhere in these files or it
does not exist. So every SCREAMING_SNAKE identifier used anywhere in
the project is checked against every one declared anywhere in it, with
comments and strings blanked, and the platform's own names allowed.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"

DECLARED = re.compile(
    r"\b(?:const\s+)?(?:val|var)\s+([A-Z][A-Z0-9_]{2,})\b|"
    r"\b([A-Z][A-Z0-9_]{2,})\s*\(|"            # an enum constant with arguments
    r"^\s*([A-Z][A-Z0-9_]{2,})\s*(?:,|;|$)",   # a bare enum constant
    re.M,
)
USED = re.compile(r"\b([A-Z][A-Z0-9_]{2,})\b")

# Anything the platform, the libraries or Java itself supplies. A name
# here is never reported; the cost of a missing entry is one false
# positive that this comment explains how to fix.
ALLOWED_SUFFIXES = (
    "_E", "_PI",
)
ALLOWED = {
    "UTF", "URL", "URI", "JSON", "XML", "HTML", "HTTP", "HTTPS", "API",
    "SQL", "DNS", "TLS", "SSL", "PDF", "CSV", "GPS", "EXIF", "MGRS",
    "ASCII", "NFD", "NFC", "NFKD", "NFKC", "US", "UK", "UTC", "GMT",
    "MODE_PRIVATE", "VERSION_CODES", "VERSION_NAME", "VERSION_CODE",
    "TIRAMISU", "O", "P", "Q", "R", "S", "MAX_VALUE", "MIN_VALUE",
    "TYPE", "INSTANCE", "CASE_INSENSITIVE", "DOT_MATCHES_ALL",
    "IGNORE_CASE", "MULTILINE", "COMMENTS", "LITERAL", "UNIX_LINES",
    "CANON_EQ", "MATCH_PARENT", "WRAP_CONTENT", "FLAG_SECURE",
    "OPEN_READONLY", "OPEN_READWRITE", "CREATE_IF_NECESSARY",
    "NO_LOCALIZED_COLLATORS", "ENABLE_WRITE_AHEAD_LOGGING",
}


def main() -> int:
    files = sorted(SRC.rglob("*.kt"))
    texts = {p: without_prose(p.read_text(errors="replace")) for p in files}

    declared: set[str] = set()
    for text in texts.values():
        for m in DECLARED.finditer(text):
            for g in m.groups():
                if g:
                    declared.add(g)
        # Enum entries, which are constants wherever they are written.
        # The regex above only catches the ones on a line of their own
        # or carrying arguments; `enum class Kind { HIT, ABSENT }` on
        # one line declares two and matches neither.
        for m in re.finditer(r"\benum\s+class\s+\w+[^{]*\{", text):
            depth = 0
            start = m.end() - 1
            for i in range(start, len(text)):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        body = text[start + 1:i]
                        declared.update(re.findall(r"\b[A-Z][A-Z0-9_]{2,}\b", body))
                        break

    bad: list[str] = []
    for path, text in texts.items():
        for m in USED.finditer(text):
            name = m.group(1)
            if name in declared or name in ALLOWED:
                continue
            if name.endswith(ALLOWED_SUFFIXES):
                continue
            # A name reached through a dot is somebody else's.
            before = text[:m.start()].rstrip()
            if before.endswith((".", "::", "@")):
                continue
            line = text.count("\n", 0, m.start()) + 1
            bad.append(
                f"{path.relative_to(ROOT)}:{line}: {name} is used here and "
                f"declared nowhere in the project"
            )

    if bad:
        print("Constants used and never declared:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(f"missing-constants: {len(declared)} declared, every use resolves.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
