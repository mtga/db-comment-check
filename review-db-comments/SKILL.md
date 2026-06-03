---
name: review-db-comments
description: ALWAYS run this before committing changes or reviewing a diff/PR. Scans the changed files for newly added or modified Liquibase migrations (changelogs) and makes sure every newly created table and column has a comment, adding a meaningful one in place if it is missing. Use before any `git commit`, when reviewing staged changes, or when asked about liquibase comments / COMMENT ON / remarks / missing table or column comments.
---

# review-db-comments

A pre-commit / pre-review check: **no new database table or column ships without a comment.**
Run it as part of every commit or diff review. If the change set touches no Liquibase migration,
it's a two-second no-op; if it adds tables/columns, you fix them before the commit lands.

This is the author-side counterpart to the build-time `db-comment-check` guard
(`db-comment-check/src/main/java/db/commentcheck/SqlCommentAnalyzer.java`) — same rule, applied
early so the build stays green.

## When to run

Whenever you are about to **commit** or **review a diff/PR**, before finalizing it. No script and
no build needed — you read the diff and reason about it directly.

## Procedure

1. **Find newly added or modified migrations.** Look at what's changed:
   ```bash
   git diff --cached --name-status        # staged (about to commit)
   git diff --name-status                 # unstaged, if reviewing the working tree
   ```
   Keep the changelog files: `.sql`, `.xml`, `.yaml`/`.yml`, `.json` under a
   `changelog` / `migration` / `db/` path. If none, you're done.

2. **Read each one and list the objects it creates** (look only at *added* content — `+` lines for
   modified files; the whole file for new ones):
   - `CREATE TABLE <t> ( ... )` → the table **and each column**. Skip constraint rows
     (`CONSTRAINT`, `PRIMARY`/`FOREIGN` KEY, `UNIQUE`, `CHECK`, `EXCLUDE`, `LIKE`).
   - `ALTER TABLE <t> ADD [COLUMN] <c>` → the column `t.c`. Skip `ADD CONSTRAINT ...`.
   - Structured `<createTable>` / `<addColumn>` (XML/YAML/JSON) → the table and its `<column>`s.

3. **Check each created object for a comment:**
   - SQL changelogs → a `COMMENT ON TABLE <t> IS '...'` / `COMMENT ON COLUMN <t>.<c> IS '...'`
     for that object.
   - Structured changelogs → a non-empty `remarks` on the `<createTable>` / `<column>` /
     `<addColumn><column>` (or a `setTableRemarks` / `setColumnRemarks` change). Liquibase renders
     `remarks` into `COMMENT ON`, so it counts.
   - Identifiers compare case-insensitively, schema- and quote-insensitively:
     `"public"."Customer"` ≡ `customer`.

4. **Add the missing comments, in the file's own format**, writing concise text inferred from the
   object name and surrounding columns/migration intent. Don't invent business meaning you can't
   see — describe what the column structurally is.

   SQL — append inside the **same changeset** as the CREATE/ALTER:
   ```sql
   CREATE TABLE customer (
       id BIGINT PRIMARY KEY,
       email VARCHAR(255) NOT NULL
   );
   COMMENT ON TABLE customer IS 'Registered customers of the service';
   COMMENT ON COLUMN customer.id IS 'Surrogate primary key';
   COMMENT ON COLUMN customer.email IS 'Login email address, unique per customer';
   ```

   Structured (XML shown; YAML uses `remarks:`, JSON uses `"remarks"`):
   ```xml
   <createTable tableName="orders" remarks="Customer orders">
       <column name="id"     type="bigint"        remarks="Surrogate primary key"/>
       <column name="total"  type="numeric(19,2)" remarks="Order total in minor currency units"/>
       <column name="status" type="varchar(20)"   remarks="Order lifecycle status (NEW, PAID, ...)"/>
   </createTable>
   ```

5. **Respect intentional opt-outs.** If a changeset carries the marker `no-db-comment` (in its
   `<comment>` / `--comment`), leave it alone. Likewise skip objects the team has explicitly
   exempted.

6. **Re-stage and report.** `git add` the edited files and give a short summary of every object you
   commented and the text you used, so the dev can refine a guess before the commit lands. Don't
   commit on the dev's behalf unless asked.

## Notes

- Source of truth is the change set being committed/reviewed — only flag objects the *current*
  change introduces. Pre-existing tables/columns in a touched file are grandfathered; don't
  retro-comment the whole file.
- Constraints are never columns. `ALTER TABLE ... ADD CONSTRAINT` and `CONSTRAINT`/`UNIQUE`/`CHECK`
  rows inside `CREATE TABLE` are not flagged.
- A table or column comment that's clearly a placeholder (`'TODO'`, empty string) doesn't count as
  commented — treat it as missing.
