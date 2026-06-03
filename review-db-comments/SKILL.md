---
name: review-db-comments
description: Review, check, and fix missing comments on Liquibase migration scripts before commit. Use when asked to review/lint/check staged Liquibase changelogs, or to add missing COMMENT ON / remarks to newly created tables and columns. Triggers on "db comment review", "liquibase comments", "missing table/column comments", "comment my migration".
---

# review-db-comments

Author-side guard for Liquibase migrations. It finds staged changesets that **create a table
or add a column without a comment** and edits the migration to add a meaningful one — *before*
you commit. It is the fix-it-now counterpart to the build-time `db-comment-check` guard
(`db-comment-check/src/main/java/db/commentcheck/SqlCommentAnalyzer.java`); both use the same
detection rules, so a clean run here means that guard stays green in CI.

Detection is driven by a self-contained Python 3 helper (no Java/Gradle/JDBC, no DB). The agent
runs it, reads the worklist, then writes the comment text — which is the part a script can't do.

> Paths below are relative to the repo root you run the skill in. The helper lives at
> `.claude/skills/review-db-comments/detect_missing_comments.py`.

## Run (agent path) — do this first

Stage your migration as usual (`git add ...`), then list what's missing:

```bash
python .claude/skills/review-db-comments/detect_missing_comments.py
```

It scans staged changelog files (`git diff --cached`, added + modified, `.sql/.xml/.yaml/.yml/.json`
under a `changelog`/`migration`/`db/` path) and prints one block per file. Example output:

```
FILE: db/changelog/001-create.sql   [format=sql, status=A]
  MISSING TABLE   customer
  MISSING COLUMN  customer.id
  MISSING COLUMN  customer.email
  MISSING COLUMN  customer.last_login

FILE: db/changelog/002-create.xml   [format=xml, status=A]
  MISSING COLUMN  orders.total
  MISSING COLUMN  orders.status

6 object(s) missing comments across the staged changelogs above.
```

Exit code: `0` = nothing missing (prints `OK: no staged changelog objects are missing comments.`),
`1` = missing comments, `2` = not a git repo / git error. Each `MISSING` line is one object you
must comment.

Useful flags (all optional):

```bash
# restrict to a directory, ignore known objects, scan working tree instead of staged
python .claude/skills/review-db-comments/detect_missing_comments.py --prefix db/changelog
python .claude/skills/review-db-comments/detect_missing_comments.py --ignore some_join_table,t.legacy_col
python .claude/skills/review-db-comments/detect_missing_comments.py --all
# analyze specific files directly, bypassing git (uses on-disk content)
python .claude/skills/review-db-comments/detect_missing_comments.py --paths db/changelog/001-create.sql
```

## Fix procedure

For every `MISSING` object, open its migration and add the comment **in that file's own format**,
writing text inferred from the object name + surrounding columns/migration intent. Keep it short and
factual; don't invent business meaning you can't see.

**SQL-formatted changelogs (`.sql`)** — append `COMMENT ON` statements *inside the same changeset*
as the `CREATE TABLE` / `ALTER TABLE ... ADD`:

```sql
--changeset alice:1
CREATE TABLE customer (
    id BIGINT PRIMARY KEY,
    email VARCHAR(255) NOT NULL
);
ALTER TABLE customer ADD COLUMN last_login TIMESTAMP;
COMMENT ON TABLE customer IS 'Registered customers of the service';
COMMENT ON COLUMN customer.id IS 'Surrogate primary key';
COMMENT ON COLUMN customer.email IS 'Login email address, unique per customer';
COMMENT ON COLUMN customer.last_login IS 'Timestamp of the most recent successful login';
```

**Structured changelogs (`.xml` / `.yaml` / `.json`)** — add a `remarks` attribute/key to the
`<createTable>` / `<column>` / `<addColumn><column>`. Liquibase renders `remarks` to `COMMENT ON`
for Postgres/Oracle, so this satisfies the build guard too. XML:

```xml
<createTable tableName="orders" remarks="Customer orders">
    <column name="id" type="bigint" remarks="Surrogate primary key"/>
    <column name="total" type="numeric(19,2)" remarks="Order total in minor currency units"/>
    <column name="status" type="varchar(20)" remarks="Order lifecycle status (NEW, PAID, SHIPPED)"/>
</createTable>
```

YAML uses `remarks:` keys on the table and each `column:`; JSON uses `"remarks": "..."`.

**Opt-out:** if a changeset is intentionally uncommented, add the marker `no-db-comment` to its
changeset `<comment>` (XML/YAML/JSON) or a `--comment ... no-db-comment` line (SQL). The helper
skips it. You can also pass `--ignore` for specific objects.

## Verify and finish

Re-stage the edited files and re-run until clean:

```bash
git add -A
python .claude/skills/review-db-comments/detect_missing_comments.py
```

Expected:

```
OK: no staged changelog objects are missing comments.
```

Then report a short summary table of every object you commented and the text you used, so the dev
can refine any guesses before committing. **Do not commit for the dev** unless asked.

## Gotchas

- **Source of truth is the *staged* content**, not the working tree. The helper reads `git show
  :<path>`, so unstaged edits are invisible until you `git add`. (`--all` switches to the working
  tree for ad-hoc scans.)
- **Modified files are scoped to added lines.** For a changelog with status `M`, only objects
  introduced on staged `+` lines are flagged; pre-existing tables/columns in the same file are
  grandfathered. Verified: appending `ALTER TABLE customer ADD COLUMN phone ...` to an already-
  committed file flags only `customer.phone`, not the legacy `customer.*` columns.
- **Line-level caveat:** a changeset *appended* to an existing file with no other change to its
  surrounding lines is detected via its `+` lines; but if your diff tooling produces no added-line
  context for it, it can be missed — same limitation the Java guard documents. Whole new files are
  always fully analyzed.
- **Constraints are not columns.** `CONSTRAINT`/`PRIMARY`/`FOREIGN`/`UNIQUE`/`CHECK`/`EXCLUDE`/`LIKE`
  rows in a `CREATE TABLE`, and `ALTER TABLE ... ADD CONSTRAINT`, are never flagged.
- **Identifiers are normalized:** quotes/backticks/brackets stripped, schema dropped, lower-cased —
  `"public"."Customer"` is treated as `customer`. Your comment target must resolve to the same key.
- **YAML parsing is best-effort** (no PyYAML in stdlib): it tracks `createTable`/`addColumn` blocks
  by `remarks:` presence. For unusual YAML layouts, confirm the helper's output against the file by
  eye before trusting it. XML and JSON are parsed structurally and are reliable.
- A malformed changelog prints `PARSE ERROR <msg>` instead of crashing the run — open that file and
  fix the syntax first.

## Troubleshooting

- **`error: ... Run this inside a git repository with staged changes` (exit 2):** you're not in a
  git repo, or nothing is staged. `cd` to the repo and `git add` your migration first.
- **Helper reports nothing but you have new migrations:** they may not match the path filter. Check
  the file is under a `changelog`/`migration`/`db/` path, or pass `--prefix <your-dir>` /
  `--paths <file>`.
- **`python` not found:** try `python3`. The script is stdlib-only and needs Python 3.8+.
