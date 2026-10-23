#!/usr/bin/env python3
"""Room's migrations and Room's entities have to agree exactly, and
nothing checks that until the database is opened on a phone.

When they disagree Room throws on open with "Migration didn't properly
handle: <table>", and the app's own recovery then has to decide what to
do with a database it cannot read. That is the worst failure this app
has — it is somebody's case file — and it is caused by a typo in a
CREATE TABLE that no compiler reads, because the SQL is a string.

So this reads both sides and compares them: every CREATE TABLE in a
migration against the @Entity whose tableName matches, column by column,
including whether each one is NOT NULL. Columns added by ALTER TABLE in
a later migration count as present.

It checks shape, not history: a column the entity has and no migration
creates is reported, and so is the reverse."""
import re
import sys
import pathlib

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
ENTITIES = ROOT / "app/src/main/java/com/osint/unified/data/db/Entities.kt"
DATABASE = ROOT / "app/src/main/java/com/osint/unified/data/db/AppDatabase.kt"

# Kotlin type -> the SQLite affinity Room uses for it.
AFFINITY = {
    "Long": "INTEGER", "Int": "INTEGER", "Boolean": "INTEGER",
    "Short": "INTEGER", "Byte": "INTEGER",
    "String": "TEXT", "Double": "REAL", "Float": "REAL",
    "ByteArray": "BLOB",
}


def entities(text):
    """Every @Entity in the file as (table, {column: (affinity, notnull)})."""
    out = {}
    # Each entity is an @Entity annotation followed by a data class whose
    # parameter list ends at the first line that is just ")".
    for m in re.finditer(r"@Entity\s*\((.*?)\)\s*\n(?:@\w+[^\n]*\n)*data class\s+(\w+)\s*\(",
                         text, re.S):
        annotation, cls = m.group(1), m.group(2)
        name = re.search(r'tableName\s*=\s*"([^"]+)"', annotation)
        table = name.group(1) if name else None
        if not table:
            continue
        body, depth, i = [], 1, m.end()
        while i < len(text) and depth:
            ch = text[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            body.append(ch)
            i += 1
        columns = {}
        # Strip comments and annotations before reading the parameters.
        src = re.sub(r"/\*.*?\*/", " ", "".join(body), flags=re.S)
        src = re.sub(r"//[^\n]*", " ", src)
        src = re.sub(r"@\w+(\([^)]*\))?", " ", src)
        for prop in re.finditer(
            r"\bva[lr]\s+(\w+)\s*:\s*([\w<>,?]+?)\s*(?:=|,|$)", src, re.M
        ):
            field, ktype = prop.group(1), prop.group(2).strip()
            nullable = ktype.endswith("?")
            base = ktype.rstrip("?").strip()
            columns[field] = (AFFINITY.get(base, "?" + base), not nullable)
        out[table] = (cls, columns)
    return out


def created(text):
    """Tables a migration creates, as (table, {column: (type, notnull)})."""
    out = {}
    for m in re.finditer(
        r"CREATE TABLE IF NOT EXISTS\s+`(\w+)`\s*\((.*?)\n\s*\)\s*\n", text, re.S
    ):
        table, body = m.group(1), m.group(2)
        columns = {}
        for line in body.split("\n"):
            line = line.strip().rstrip(",")
            col = re.match(r"`(\w+)`\s+(\w+)(.*)$", line)
            if not col:
                continue
            columns[col.group(1)] = (
                col.group(2).upper(), "NOT NULL" in col.group(3).upper()
            )
        out[table] = columns
    return out


def added(text):
    """Columns added later, as {table: {column: (type, notnull)}}."""
    out = {}
    for m in re.finditer(
        r"ALTER TABLE\s+`?(\w+)`?\s+ADD COLUMN\s+`?(\w+)`?\s+(\w+)([^\"']*)", text
    ):
        table, column, kind, rest = m.groups()
        out.setdefault(table, {})[column] = (kind.upper(), "NOT NULL" in rest.upper())
    return out


def main():
    if not ENTITIES.exists() or not DATABASE.exists():
        print("migration-schema: nothing to check.")
        return 0
    ent = entities(ENTITIES.read_text(encoding="utf-8"))
    db_text = DATABASE.read_text(encoding="utf-8")
    made, extra = created(db_text), added(db_text)

    problems = []
    for table, (cls, columns) in sorted(ent.items()):
        if table not in made:
            # Created by Room itself on a fresh install and by an older
            # migration this file no longer holds. Nothing to compare.
            continue
        have = dict(made[table])
        have.update(extra.get(table, {}))
        for column, (affinity, notnull) in sorted(columns.items()):
            if column not in have:
                problems.append(
                    f"{cls}.{column} has no column in the `{table}` migration"
                )
                continue
            sql_type, sql_notnull = have[column]
            if affinity.startswith("?"):
                problems.append(
                    f"{cls}.{column} is {affinity[1:]}, which Room stores "
                    f"through a converter — check `{table}` by hand"
                )
            elif sql_type != affinity:
                problems.append(
                    f"{cls}.{column} is {affinity} but `{table}` declares {sql_type}"
                )
            if notnull != sql_notnull:
                want = "NOT NULL" if notnull else "nullable"
                got = "NOT NULL" if sql_notnull else "nullable"
                problems.append(
                    f"{cls}.{column} is {want} but `{table}` declares it {got}"
                )
        for column in sorted(set(have) - set(columns)):
            problems.append(f"`{table}`.{column} exists in SQL but not on {cls}")

    checked = sum(1 for t in ent if t in made)
    if problems:
        print("migration-schema: a migration and its entity disagree —")
        for p in problems:
            print("  " + p)
        return 1
    print(
        f"migration-schema: {checked} migrated "
        f"table{'' if checked == 1 else 's'} match their entities."
    )
    return 0


sys.exit(main())
