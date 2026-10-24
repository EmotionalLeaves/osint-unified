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

# Errors that no classpath can explain away. Everything in this list is
# about the shape of the code rather than about what a name refers to, so
# it is real whether or not the Android SDK is present.
#
# "A 'return' expression required in a function with a block body" earned
# its place the hard way: turning an expression-bodied function into a
# block-bodied one silently drops the result, kotlinc says so plainly,
# and the message was being filtered out with the six thousand unresolved
# AndroidX references because it happened to mention types it could not
# resolve.
grep -iE "error:.*(conflicting declaration|redeclaration|expecting |unexpected token|unbalanced|unclosed|syntax error|already defined|expecting an element|expecting a top level|expecting member declaration|return. expression required|missing return statement|cannot be reassigned|variable expected|modifier .* is not applicable|annotation is not applicable)" \
  "$WORK/full.txt" > "$WORK/real.txt" || true

# A call that does not supply an argument the function requires.
#
# Nothing about this needs a classpath when the function is ours, and
# it is the error that broke r269 and r270: adding a parameter with a
# default and then passing it by name *before* the positional
# arguments, which Kotlin reads as the positional ones having been
# skipped. Thirty-three call sites, all wrong, and the pre-flight said
# nothing.
#
# Two parameter names are excluded, and both for the same reason: the
# offline compiler resolves an AndroidX name to a stdlib one that
# happens to need an argument the AndroidX one does not.
# `Result.success()` becomes kotlin.Result.success(value), and a
# coroutine builder whose lambda mentions an unresolvable symbol
# reports as a missing `block`. The cost is that a genuinely missing
# argument called `value` or `block` is not caught here; the broad
# case, which is the one that cost two builds, still is.
grep -iE "error: no value passed for parameter" "$WORK/full.txt" \
  | grep -vE "parameter '(block|value)'" >> "$WORK/real.txt" || true

# An annotation left behind by an insertion.
#
# Kotlin requires KDoc *before* annotations, so "@OptIn(...)" followed
# by "/**" is always an annotation that has become detached from the
# declaration it was written for — which is exactly what happens when
# something is inserted above a function. It stays valid Kotlin, so
# the local compiler says nothing, and CI then fails with one error
# per experimental call in the whole file. Two builds.
python3 - <<'PYEOF' >> "$WORK/real.txt" || true
import os, re, sys
roots = ["app/src/main/java", "app/src/test/java"]
for root in roots:
    for dirpath, _, files in os.walk(root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(dirpath, f)
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
            for i, line in enumerate(lines[:-1]):
                if not line.lstrip().startswith("@"):
                    continue
                nxt = lines[i + 1].lstrip()
                if nxt.startswith("/*"):
                    print("error: annotation detached from its declaration "
                          "(KDoc may not follow an annotation): "
                          "{}:{}: {}".format(path, i + 1, line.strip()))
PYEOF

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
grep -nE "error: [Uu]nresolved reference:? '?@[A-Za-z]" $(find app/src/main/java app/src/test/java -name '*.kt') 2>/dev/null \
  | sed 's/^/error: /' >> "$WORK/real.txt" || true
grep -E "error: [Uu]nresolved reference:? '?@[A-Za-z]" "$WORK/full.txt" >> "$WORK/real.txt" || true

# A reference ending in an underscore is nearly always "$name_" in a string
# template, where Kotlin swallows the underscore into the identifier.
grep -E "error: [Uu]nresolved reference:? '?[A-Za-z0-9]+_'?\\.?$" "$WORK/full.txt" >> "$WORK/real.txt" || true

# A workflow condition that reads the `secrets` context. GitHub does not
# make `secrets` available to a step's `if`, and a workflow that uses it is
# rejected before any job starts — so the run fails with no job, no log and
# no annotation, which is how r177 reported itself. Secrets reach a
# condition through a job-level `env`.
grep -nE "^\s*if:.*secrets\." .github/workflows/*.yml 2>/dev/null \
  | sed 's/^/error: `secrets` is not available to a workflow `if` — map it through job-level env: /' \
  >> "$WORK/real.txt" || true

# The Room schema version is written twice — once in the @Database
# annotation, which cannot reference a constant declared inside the class
# it annotates, and once as SCHEMA_VERSION for the recovery code. If they
# drift, the recovery code probes the wrong range and a database one
# version newer than it looks for is reported as unreadable.
DB=app/src/main/java/com/osint/unified/data/db/AppDatabase.kt
ANNOTATED=$(grep -oE "^\s*version = [0-9]+" "$DB" | grep -oE "[0-9]+" | head -1)
DECLARED=$(grep -oE "const val SCHEMA_VERSION = [0-9]+" "$DB" | grep -oE "[0-9]+" | head -1)
if [ -n "$ANNOTATED" ] && [ "$ANNOTATED" != "$DECLARED" ]; then
  echo "error: @Database(version = $ANNOTATED) but SCHEMA_VERSION = $DECLARED" >> "$WORK/real.txt"
fi

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

# An annotation separated from what it annotates by a doc comment.
#
# Kotlin allows it and then applies the annotation to whatever follows
# the comment, which is how a @Composable that belonged to a function
# ended up on the property declared above it — and the compiler's
# complaint is about applicability, so it needs the real classpath and
# does not appear here. Nothing legitimate writes an annotation, then a
# KDoc, then the declaration.
BADANNOT=$(awk '
  /^[[:space:]]*@[A-Za-z]/ && !/\(/ { ann = FILENAME ":" FNR; next }
  ann && /^[[:space:]]*\/\*\*/ { print ann " annotation separated from its declaration by a doc comment"; ann = ""; next }
  { ann = "" }
' $(find app/src/main/java app/src/test/java -name '*.kt') 2>/dev/null)
if [ -n "$BADANNOT" ]; then
  printf '%s\n' "$BADANNOT" >> "$WORK/real.txt"
fi


# One thing this cannot catch, written down so it is not re-attempted.
#
# r292 died on a reference to `q` where the local was called `query`.
# Nothing here found it, and nothing here can: a name that exists
# nowhere in this project is exactly what an unresolved AndroidX
# symbol looks like, and there are eleven thousand of those. Measured
# rather than assumed — filtering to names never bound anywhere in
# their own file still leaves 539 distinct ones, almost all of them
# library symbols. Short names do no better: the commonest are `dp`,
# `it` and `sp`.
#
# A check that fires on all of those would be ignored within a day,
# which is the same failure as a check that never fires. That one
# belongs to CI.

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
# A Compose modifier that is an extension function, used in a file that
# does not import it. Invisible to the classpath-less compile, which
# writes every unresolved name after a dot off as the missing SDK.
python3 "$ROOT/tools/modifier-imports.py" || exit 1

echo
# The same idea, for kotlinx.serialization's extension properties.
# `element.jsonPrimitive` is an extension, so a file that uses it
# without the import compiles nowhere — and the classpath-less run
# writes it off with the missing SDK. r298 died on one line of it.
python3 "$ROOT/tools/serialization-imports.py" || exit 1

echo
python3 "$ROOT/tools/java-keywords.py" || exit 1

echo
python3 "$ROOT/tools/migration-schema.py" "$ROOT" || exit 1

echo
python3 "$ROOT/tools/optin-coverage.py" "$ROOT/app/src/main/java" || exit 1

echo
python3 "$ROOT/tools/padding-overloads.py" "$ROOT/app/src/main/java" || exit 1

echo
python3 "$ROOT/tools/classify-prefixes.py" || exit 1
echo
python3 "$ROOT/tools/shadowed-stdlib.py" || exit 1
echo
python3 "$ROOT/tools/init-order.py" || exit 1
echo
python3 "$ROOT/tools/missing-constants.py" || exit 1
echo
python3 "$ROOT/tools/novel-calls.py" || true
