#!/usr/bin/env bash
#
# Pre-flight check for the blind build loop.
#
# This project is built entirely in CI, so a trivial Kotlin mistake costs a
# full round trip — and GitHub's runners can be queued for an hour. The
# Android SDK is not available locally, so a real compile is impossible,
# but a surprising number of mistakes are classpath-independent and can be
# caught here in seconds:
#
#   * parser errors (unbalanced braces, bad string templates)
#   * conflicting declarations and redeclarations — the duplicated
#     constructor parameter that broke r84
#   * "$name_" string templates, where Kotlin reads the trailing underscore
#     as part of the identifier — the bug in the address permutator
#   * a bare identifier declared in this project but not visible where it
#     is used — a member added to the wrong class (see
#     tools/unresolved-locals.py)
#
# Everything else (unresolved AndroidX symbols, type inference) needs the
# SDK and is left to CI, so those diagnostics are counted and ignored.
#
# Usage:  tools/syntaxcheck.sh [path-to-kotlinc]
#   Get kotlinc from https://github.com/JetBrains/kotlin/releases

set -uo pipefail

KOTLINC="${1:-${KOTLINC:-kotlinc}}"
if ! command -v "$KOTLINC" >/dev/null 2>&1 && [ ! -x "$KOTLINC" ]; then
  echo "kotlinc not found. Pass its path or set KOTLINC." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# shellcheck disable=SC2046
"$KOTLINC" -J-Xmx4g $(find app/src/main/java app/src/test/java -name '*.kt') \
  -nowarn -d "$WORK/out" > "$WORK/full.txt" 2>&1

grep -iE "error:.*(conflicting declaration|redeclaration|expecting |unexpected token|unbalanced|unclosed|syntax error|already defined|expecting an element|expecting a top level|expecting member declaration)" \
  "$WORK/full.txt" > "$WORK/real.txt" || true

# ${'$'}{...} — a literal dollar followed by a brace. This is what a string
# template becomes when it is written out through a shell heredoc that has
# already escaped the dollar, and the result compiles: Kotlin reads it as the
# constant text "${...}" with no interpolation at all. It cost a silent bug
# where a de-duplication key was the same string every time, so the app ran
# one source per search instead of twenty. Nothing legitimate needs this
# form, since a literal dollar before a brace can be written \${.
grep -n "\${'\$'}{" $(find app/src/main/java app/src/test/java -name '*.kt') 2>/dev/null \
  | sed 's/^/error: literal-dollar template (heredoc damage): /' >> "$WORK/real.txt" || true

# An unresolved LABEL. Labels are resolved without any classpath, so unlike
# the thousands of unresolved AndroidX symbols this one is always real: it
# means a `return@foo` naming a scope that does not enclose it. It broke r150,
# where a return@withContext ended up inside a coroutineScope and vice versa.
grep -nE "error: unresolved reference: @[A-Za-z]" $(find app/src/main/java app/src/test/java -name '*.kt') 2>/dev/null \
  | sed 's/^/error: /' >> "$WORK/real.txt" || true
grep -E "error: unresolved reference: @[A-Za-z]" "$WORK/full.txt" >> "$WORK/real.txt" || true

# A reference ending in an underscore is nearly always "$name_" in a string
# template, where Kotlin swallows the underscore into the identifier.
grep -E "error: unresolved reference: [A-Za-z0-9]+_$" "$WORK/full.txt" >> "$WORK/real.txt" || true

# A Compose scope member imported as though it were a top-level function.
# `Modifier.weight` belongs to ColumnScope and is internal at package level,
# so importing it compiles locally and fails in CI with "it is internal" —
# which is how r168 died. These names are only ever reached through their
# scope receiver.
grep -nE "^import androidx\.compose\.(foundation\.layout|ui)\.(weight|align|alignBy|alignByBaseline|matchParentSize|fillParentMaxSize|fillParentMaxWidth|fillParentMaxHeight|animateItemPlacement)$" \
  $(find app/src -name '*.kt') 2>/dev/null \
  | sed 's/^/error: scope member imported as a top-level function: /' >> "$WORK/real.txt" || true

# A bare identifier that is declared somewhere in this project but is not
# visible where it is used — a member added to the wrong class, an import
# never written. Unlike the AndroidX noise, this one is genuinely broken,
# and CI was the only thing catching it.
python3 "$ROOT/tools/unresolved-locals.py" "$WORK/full.txt" >> "$WORK/real.txt" || true

TOTAL=$(grep -c 'error:' "$WORK/full.txt" || true)

if [ -s "$WORK/real.txt" ]; then
  echo "Problems that do not need the Android SDK to be wrong:"
  cat "$WORK/real.txt"
  echo
  echo "($TOTAL diagnostics in total; the rest need the SDK and are left to CI.)"
  exit 1
fi

echo "No syntax or declaration problems."
echo "($TOTAL diagnostics in total, all needing the Android SDK — left to CI.)"
echo
# Not an error — a list to glance at. A member call introduced by this
# change and found nowhere else is either a library API used for the first
# time or a helper that was never written, and the second one has now
# failed two builds.
# A member named on a project singleton that the singleton does not
# declare. Fully decidable from the source, and invisible to the
# classpath-less compile, which writes it off as missing-SDK noise.
python3 "$ROOT/tools/object-members.py" || exit 1
echo
python3 "$ROOT/tools/novel-calls.py" || true
