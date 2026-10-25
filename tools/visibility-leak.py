#!/usr/bin/env python3
"""A public declaration whose signature names an internal type.

Kotlin refuses "public function exposes its internal parameter type", and
the local check cannot see it: every unresolved name is treated as
missing-SDK noise, so the file compiles here and dies in CI. That is how
r509 died, over a class that had no modifier and a parameter type that
did.

This is the third shape of the same mistake. object-members.py already
catches a private companion read from another file, and a visible
companion member with a private-in-class type. Both were found the
expensive way, one commit apart, and so was this one.

The rule: a top-level declaration with no visibility modifier is public,
and a public signature may not name a type declared `internal` anywhere
in the project. Opt out with `// visibility-leak: <why>` on the line
above.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ktscan import without_prose  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main"

INTERNAL_TYPE = re.compile(
    r"^\s*internal\s+(?:data\s+|sealed\s+|value\s+|enum\s+|annotation\s+|abstract\s+|open\s+)*"
    r"(?:class|interface|object|typealias)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)

# Any declaration, at any indent. Members of a public class are public
# too — which is the half the first draft of this check missed, on the
# exact failure it was written for: `class TileStore` is top level and
# `fun peek(ref: Tiles.Ref)` is two levels in, and it was the member
# that CI refused.
DECL = re.compile(
    r"^([ \t]*)((?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:expect\s+|actual\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+|enum\s+"
    r"|annotation\s+|inline\s+|suspend\s+|override\s+|operator\s+|infix\s+|const\s+"
    r"|lateinit\s+|external\s+|tailrec\s+)*"
    r"(class|interface|object|fun|val|var)\s+)",
    re.M,
)

HIDDEN = re.compile(r"\b(?:private|internal|protected)\b")


def signature_at(code: str, start: int) -> str:
    """From a declaration keyword to the end of its signature.

    Stops at the body, the initialiser, or the end of the line when
    there is neither — and follows parentheses across lines, because a
    parameter list formatted one-per-line is the normal shape in this
    project and reading only the first line of it sees no types at all.
    """
    depth = 0
    i = start
    n = len(code)
    while i < n:
        c = code[i]
        # Parentheses and square brackets only. Angle brackets are
        # not brackets in Kotlin: `(Double, Double) -> Unit` closes
        # one that was never opened, depth went negative, the body
        # brace stopped being a stop, and the "signature" ran to the
        # end of the function — which is how the first run of this
        # check reported four composables for naming a type in their
        # own bodies.
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        elif depth == 0:
            if c in "{=;":
                break
            if c == "\n":
                # A signature continues onto the next line only while
                # something is still open, or the line ended on a colon
                # or comma.
                if code[max(start, i - 2):i].rstrip().endswith((":", ",")):
                    i += 1
                    continue
                break
        i += 1
    return code[start:i]


def strip_parens(text: str) -> str:
    """Balanced (...) and [...] emptied, innermost first."""
    prev = None
    while prev != text:
        prev = text
        text = re.sub(r"\([^()]*\)", "()", text)
        text = re.sub(r"\[[^\[\]]*\]", "[]", text)
    return text


HEADER_TOKEN = re.compile(
    r"\b(class|interface|object|fun|init|get|set|constructor)\b|->|="
)
MODIFIERS_ON_LINE = re.compile(r"\b(?:private|internal|protected)\b")


def scopes(code: str) -> list[tuple[int, int, bool, bool]]:
    """Every brace block: (open, close, is_type_body, hidden).

    A type body is the body of a named class, interface or object, or a
    companion — the places a declaration is a member, visible wherever
    its container is. Anything else (a function body, a lambda, an
    initialiser, an anonymous object, an enum entry's body) makes what
    is inside it local. Hidden means the type was declared private,
    internal or protected, so nothing in it is public API.

    Read from the braces, not the indent. The indent rule stopped one
    level in, so a companion's members — two levels in, and as public
    as the class — were never looked at.
    """
    out: list[tuple[int, int, bool, bool]] = []
    stack: list[tuple[int, bool, bool]] = []
    paren = 0
    for i, c in enumerate(code):
        if c in "([":
            paren += 1
        elif c in ")]":
            paren = max(0, paren - 1)
        elif c == "{":
            if paren > 0:
                stack.append((i, False, False))
                continue
            # The statement this brace opens: back to the previous
            # brace or semicolon, with bracketed text emptied so a
            # default value in a constructor does not read as `=`.
            j = max(code.rfind("{", 0, i), code.rfind("}", 0, i), code.rfind(";", 0, i))
            header = strip_parens(code[j + 1:i])
            tokens = list(HEADER_TOKEN.finditer(header))
            is_type = False
            hidden = False
            if tokens:
                last = tokens[-1]
                word = last.group(1)
                if word in ("class", "interface"):
                    is_type = True
                elif word == "object":
                    before = header[:last.start()]
                    after = header[last.end():]
                    is_type = bool(
                        re.search(r"\bcompanion\s*$", before)
                        or re.match(r"\s+[A-Za-z_]", after)
                    )
                if is_type:
                    line_start = header.rfind("\n", 0, last.start()) + 1
                    hidden = bool(MODIFIERS_ON_LINE.search(header[line_start:last.start()]))
            stack.append((i, is_type, hidden))
        elif c == "}":
            if stack:
                start, is_type, hidden = stack.pop()
                out.append((start, i, is_type, hidden))
    return out


def hidden_headers(code: str) -> list[tuple[int, int]]:
    """The constructor parentheses of private and internal classes.

    `private data class Vectors(val fine: LineSet?)` declares a property
    in parentheses, not in braces, so the brace walk never saw that it
    belongs to a private class — and Kotlin is content for a private
    class to name an internal type.
    """
    spans: list[tuple[int, int]] = []
    for m in re.finditer(
        r"^[ \t]*(?:private|internal)\s+(?:[a-z]+\s+)*class\s+[A-Za-z_][A-Za-z0-9_]*\s*(?:<[^>]*>)?\s*\(",
        code, re.M
    ):
        depth = 0
        for i in range(m.end() - 1, len(code)):
            if code[i] == "(":
                depth += 1
            elif code[i] == ")":
                depth -= 1
                if depth == 0:
                    spans.append((m.end(), i))
                    break
    return spans


def enclosing(blocks: list[tuple[int, int, bool, bool]], pos: int):
    return [b for b in blocks if b[0] < pos < b[1]]


def own_and_rest(kind: str, signature: str) -> tuple[str, str]:
    """The declared name, and the signature with it taken out.

    For an extension the receiver comes first — `fun Tiles.Ref.f()` —
    and taking the first identifier as the name meant the receiver,
    the very type that leaks, was the one thing never checked.
    """
    if kind in ("class", "interface", "object"):
        m = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)", signature)
        if not m:
            return "", signature
        return m.group(1), signature[m.end():]
    body = re.sub(r"^\s*<[^>]*>", "", signature)
    stop = re.search(r"\(|:|=|\bby\b", body)
    prefix = body[:stop.start()] if stop else body
    names = list(re.finditer(r"[A-Za-z_][A-Za-z0-9_]*", prefix))
    if not names:
        return "", signature
    last = names[-1]
    return last.group(0), prefix[:last.start()] + body[len(prefix):]


def inferred_from(code: str, end: int, kind: str, signature: str) -> str | None:
    """The type a public val or expression-bodied fun takes from its
    initialiser, when that is a plain constructor call.

    `val store = TileStore(context)` has no type in its signature for
    the check to read, and Kotlin still refuses it: the inferred type
    is the declared one. Only constructor calls are read — anything
    else needs the compiler to know what it returns.
    """
    if kind == "fun":
        flat = strip_parens(signature)
        if re.search(r"\)\s*:", flat):
            return None
    elif kind in ("val", "var"):
        if ":" in strip_parens(signature).split("=")[0]:
            return None
    else:
        return None
    tail = code[end:end + 200]
    m = re.match(
        r"(?:=|\s*by\s+lazy\s*\{|\{)?\s*(?:lazy\s*\{\s*)?([A-Z][A-Za-z0-9_]*)\s*(?:<[^>]*>)?\s*\(",
        tail,
    )
    if not m:
        return None
    if tail.startswith("{") and kind == "fun":
        return None
    if tail.startswith("{") and not re.search(r"\bby\s+lazy\s*$", signature):
        return None
    return m.group(1)


def main() -> int:
    files = sorted(SRC.rglob("*.kt"))
    internal: set[str] = set()
    for path in files:
        internal.update(INTERNAL_TYPE.findall(without_prose(path.read_text(errors="replace"))))
    if not internal:
        print("visibility-leak: no internal types declared, nothing to check.")
        return 0

    bad: list[str] = []
    for path in files:
        raw = path.read_text(errors="replace")
        code = without_prose(raw)
        raw_lines = raw.split("\n")
        blocks = scopes(code)
        headers = hidden_headers(code)
        for m in DECL.finditer(code):
            if HIDDEN.search(m.group(2)):
                continue
            around = enclosing(blocks, m.start())
            # Inside a body that is not a type's, it is a local: naming
            # an internal type there is ordinary.
            if any(not is_type for _, _, is_type, _ in around):
                continue
            if any(hidden for _, _, _, hidden in around):
                continue
            if any(a <= m.start() <= b for a, b in headers):
                continue
            line = code.count("\n", 0, m.start()) + 1
            if line >= 2 and "visibility-leak:" in raw_lines[line - 2]:
                continue
            kind = m.group(3)
            signature = signature_at(code, m.end())
            own_name, checked = own_and_rest(kind, signature)
            named = {
                ty for ty in internal
                if ty != own_name and re.search(r"\b%s\b" % re.escape(ty), checked)
            }
            inferred = inferred_from(code, m.end() + len(signature), kind, signature)
            if inferred in internal and inferred != own_name:
                named.add(inferred)
            if named:
                bad.append(
                    f"{path.relative_to(ROOT)}:{line}: public declaration names the "
                    f"internal type {sorted(named)[0]} — Kotlin refuses that"
                )

    if bad:
        print("Public signatures that name an internal type:")
        for b in sorted(set(bad)):
            print("  " + b)
        return 1
    print(
        f"visibility-leak: {len(internal)} internal types, "
        f"no public signature names one."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
