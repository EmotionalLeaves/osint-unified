#!/usr/bin/env python3
"""Shared scanning helpers for the Kotlin pre-flight checks.

One copy, because three checkers had their own and the first two were
wrong about the same thing: a string template. `"$it${s("LOWER_UOM")}"`
is one string containing a piece of code containing another string, and
a blanker that scans from quote to quote ends the outer string at the
inner one's opening quote and then reads the rest of the template as
code. That made every name inside a template look like a reference,
which is how the first run of missing-constants.py reported SPF, DMARC,
NYC and SFC as undeclared constants.
"""


def without_prose(text: str) -> str:
    """Comments and string contents blanked, newlines and offsets kept.

    Code inside a `${...}` template hole is left alone, because it is
    code: a constant named there is really named there.
    """
    out = list(text)
    i, n = 0, len(text)
    # Each entry is the brace depth at which a template hole was
    # entered, so the string it belongs to resumes at the matching `}`.
    holes: list[tuple[str, int]] = []
    depth = 0

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
            continue
        if two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
            continue
        if text[i] == "{":
            depth += 1
            i += 1
            continue
        if text[i] == "}":
            depth -= 1
            i += 1
            if holes and holes[-1][1] == depth:
                quote, _ = holes.pop()
                i = scan_string(text, out, i, quote, holes, depth, blank)
            continue
        if text[i] == '"':
            triple = text[i:i + 3] == '"""'
            quote = '"""' if triple else '"'
            blank(i, i + len(quote))
            i = scan_string(text, out, i + len(quote), quote, holes, depth, blank)
            continue
        if text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            blank(i, min(j + 1, n))
            i = min(j + 1, n)
            continue
        i += 1
    return "".join(out)


def scan_string(text, out, i, quote, holes, depth, blank):
    """Blank a string's contents from [i], stopping at its end or a hole."""
    n = len(text)
    start = i
    while i < n:
        if quote == '"' and text[i] == "\\":
            i += 2
            continue
        if text[i:i + 2] == "${":
            blank(start, i)
            holes.append((quote, depth))
            return i + 1          # the '{' is counted by the main loop
        if text[i:i + len(quote)] == quote:
            blank(start, i)
            return i + len(quote)
        if quote == '"' and text[i] == "\n":
            # An unterminated single-quoted string: give up at the line
            # end rather than blanking the rest of the file.
            blank(start, i)
            return i
        i += 1
    blank(start, n)
    return n
