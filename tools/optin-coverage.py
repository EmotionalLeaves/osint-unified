#!/usr/bin/env python3
"""
An @OptIn covers the function it is written on, and no other.

Twice now a composable has been moved, or a call added to one,
where the experimental API it uses was opted into somewhere else
in the same file — and kotlinc only says so after a full Android
compile, which is a CI round trip away. This reads the same thing
locally: for every call that needs a marker, find the function it
is in and check that function, or the file, opted in.

Deliberately conservative. It only knows about calls it has been
told about, and it only looks at top-level `fun` declarations and
`@file:OptIn`, so a nested helper inherits its outer function's
annotation the way Kotlin does.
"""
import re
import sys
from pathlib import Path

# call token -> the marker that call needs
NEEDS = {
    "FlowRow": "ExperimentalLayoutApi",
    "FlowColumn": "ExperimentalLayoutApi",
    "ContextualFlowRow": "ExperimentalLayoutApi",
    "ModalBottomSheet": "ExperimentalMaterial3Api",
    "rememberModalBottomSheetState": "ExperimentalMaterial3Api",
    "TopAppBar": "ExperimentalMaterial3Api",
    "CenterAlignedTopAppBar": "ExperimentalMaterial3Api",
    "TopAppBarDefaults": "ExperimentalMaterial3Api",
    "SearchBar": "ExperimentalMaterial3Api",
    "combinedClickable": "ExperimentalFoundationApi",
    "stickyHeader": "ExperimentalFoundationApi",
}

FUN = re.compile(r"^(\s*)(?:(?:private|internal|public|inline|suspend)\s+)*fun\s")
ANNOT = re.compile(r"^\s*@")


def check(path: Path):
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")
    file_opt = set(re.findall(r"@file:OptIn\(([^)]*)\)", text))
    file_markers = set()
    for group in file_opt:
        file_markers.update(re.findall(r"(\w+)::class", group))

    # Map each line to the top-level function that owns it, and that
    # function's markers.
    owner_markers = [None] * len(lines)
    current = None
    for i, line in enumerate(lines):
        m = FUN.match(line)
        # Top level only. A nested `fun helper()` inside a
        # composable inherits the outer function's opt-in the way
        # Kotlin does, and treating it as a boundary reported every
        # call after it as uncovered.
        if m and not m.group(1):
            # Walk back over the annotation block directly above.
            markers = set()
            j = i - 1
            while j >= 0:
                stripped = lines[j].strip()
                if not stripped or stripped.startswith("//") or \
                        stripped.startswith("*") or stripped.startswith("/*"):
                    j -= 1
                    continue
                if ANNOT.match(lines[j]) or stripped.startswith(")"):
                    # Annotations can wrap; gather the whole block.
                    block = "\n".join(lines[max(0, j - 4):i])
                    for opt in re.findall(r"@OptIn\(([\s\S]*?)\)", block):
                        markers.update(re.findall(r"(\w+)::class", opt))
                    j -= 1
                    continue
                break
            current = markers
        owner_markers[i] = current

    problems = []
    for i, line in enumerate(lines):
        code = line.split("//", 1)[0]
        for token, marker in NEEDS.items():
            if not re.search(r"\b%s\s*[(.]" % re.escape(token), code):
                continue
            if marker in file_markers:
                continue
            have = owner_markers[i] or set()
            if marker not in have:
                problems.append((i + 1, token, marker))
    return problems


def main(roots):
    total = 0
    files = 0
    for root in roots:
        for path in sorted(Path(root).rglob("*.kt")):
            files += 1
            for line_no, token, marker in check(path):
                print("%s:%d: %s needs @OptIn(%s::class) on its function"
                      % (path, line_no, token, marker))
                total += 1
    if total:
        print("\n%d experimental call(s) with no opt-in covering them." % total)
        return 1
    print("optin-coverage: %d files checked, every experimental call is "
          "covered." % files)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["app/src/main/java"]))
