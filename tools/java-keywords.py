#!/usr/bin/env python3
"""Room writes Java. Java's reserved words are not available as parameter
names, and when one is used the compiler points at a generated file that
exists only inside the build — "illegal start of expression" at a line
nobody can look at. The Kotlin compiler has nothing to say about it, so
this is the only place it can be caught before CI."""
import re
import sys
import pathlib

RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for", "goto",
    "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "package", "private", "protected", "public", "return",
    "short", "static", "strictfp", "super", "switch", "synchronized",
    "this", "throw", "throws", "transient", "try", "void", "volatile",
    "while", "_",
}

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java")
problems = []
for path in root.rglob("*.kt"):
    text = path.read_text(encoding="utf-8", errors="replace")
    if "androidx.room" not in text and "@Dao" not in text:
        continue
    in_dao = False
    for n, line in enumerate(text.splitlines(), 1):
        if re.match(r"\s*(@Dao|@Database|@Entity)", line):
            in_dao = True
        if not in_dao:
            continue
        for name in re.findall(r"[(,]\s*(?:vararg\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:", line):
            if name in RESERVED:
                problems.append(f"{path}:{n} parameter named '{name}' is a Java keyword")

if problems:
    print("java-keywords: Room generates Java and these names are reserved there —")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print("java-keywords: no Room parameter uses a Java reserved word.")
