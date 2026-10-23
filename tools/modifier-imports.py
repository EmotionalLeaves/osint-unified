#!/usr/bin/env python3
"""Compose modifiers used in a file that does not import them.

`Modifier.clip(...)` is an extension function, not a member, so a file
that uses it without importing androidx.compose.ui.draw.clip compiles
nowhere — but the local check cannot see that, because the diagnostic is
an unresolved reference after a dot and every one of those is written off
as the missing Android SDK. r184 died on exactly that, on one line.

So the handful of modifiers that are extensions, and the single import
each one needs, are checked by name. A file that calls one and does not
import it is reported.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# modifier name -> the import that provides it
EXTENSIONS = {
    "clip": "androidx.compose.ui.draw.clip",
    "clipToBounds": "androidx.compose.ui.draw.clipToBounds",
    "alpha": "androidx.compose.ui.draw.alpha",
    "rotate": "androidx.compose.ui.draw.rotate",
    "scale": "androidx.compose.ui.draw.scale",
    "shadow": "androidx.compose.ui.draw.shadow",
    "blur": "androidx.compose.ui.draw.blur",
    "background": "androidx.compose.foundation.background",
    "border": "androidx.compose.foundation.border",
    "clickable": "androidx.compose.foundation.clickable",
    "combinedClickable": "androidx.compose.foundation.combinedClickable",
    "selectable": "androidx.compose.foundation.selection.selectable",
    "toggleable": "androidx.compose.foundation.selection.toggleable",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "horizontalScroll": "androidx.compose.foundation.horizontalScroll",
    "semantics": "androidx.compose.ui.semantics.semantics",
    "testTag": "androidx.compose.ui.platform.testTag",
    "navigationBarsPadding": "androidx.compose.foundation.layout.navigationBarsPadding",
    "statusBarsPadding": "androidx.compose.foundation.layout.statusBarsPadding",
    "imePadding": "androidx.compose.foundation.layout.imePadding",
    "windowInsetsPadding": "androidx.compose.foundation.layout.windowInsetsPadding",
    "minimumInteractiveComponentSize":
        "androidx.compose.material3.minimumInteractiveComponentSize",
}


def main() -> int:
    bad = []
    for path in (ROOT / "app/src").rglob("*.kt"):
        text = path.read_text(errors="replace")
        imports = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
        for name, needed in EXTENSIONS.items():
            if needed in imports:
                continue
            # Only a call through a modifier chain, not a declaration or a
            # same-package member of some other type.
            for m in re.finditer(r"\.%s\s*\(" % re.escape(name), text):
                before = text[max(0, m.start() - 400):m.start()]
                if "Modifier" not in before.split("\n")[-1] and \
                        "Modifier" not in "\n".join(before.split("\n")[-4:]):
                    continue
                line = text.count("\n", 0, m.start()) + 1
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: Modifier.{name} needs "
                    f"`import {needed}`"
                )
                break
    if bad:
        print("Compose modifiers used without their import:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(f"modifier-imports: {len(EXTENSIONS)} extension modifiers checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
