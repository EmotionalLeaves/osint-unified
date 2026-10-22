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

# A reference ending in an underscore is nearly always "$name_" in a string
# template, where Kotlin swallows the underscore into the identifier.
grep -E "error: unresolved reference: [A-Za-z0-9]+_$" "$WORK/full.txt" >> "$WORK/real.txt" || true

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
