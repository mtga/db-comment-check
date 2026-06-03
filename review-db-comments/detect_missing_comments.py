#!/usr/bin/env python3
"""Detect Liquibase changesets that create a table/column without a comment.

This is the deterministic *gathering* half of the /review-db-comments skill. It lists the
staged changelog files for the imminent commit and, for each, prints the table/column objects
that are created but have no comment. It does NOT author comment text — the agent reads this
output and edits the migrations.

Detection mirrors db-comment-check/src/main/java/db/commentcheck/SqlCommentAnalyzer.java so a
clean run here means a green db-comment-check build:

  created  = CREATE TABLE <t>(...) -> t + each top-level column (constraints skipped);
             ALTER TABLE <t> ADD [COLUMN] <c> -> t.c
  comment  = COMMENT ON TABLE/COLUMN ... IS ...   (SQL changelogs)
             a non-empty `remarks` attribute, or a setTableRemarks/setColumnRemarks change
             (structured XML/YAML/JSON changelogs — Liquibase renders remarks to COMMENT ON)
  missing  = created and not commented and not ignored
  ignore   = a changeset whose comment contains the marker (default: no-db-comment), plus an
             explicit --ignore list of fully-qualified keys (table or table.column)

Identifiers are normalized: quotes/brackets stripped, schema prefix dropped, lower-cased, so
`"public"."Customer"` == `customer`.

Exit codes: 0 = nothing missing, 1 = missing comments found, 2 = git/usage error.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from xml.etree import ElementTree

# --- SQL patterns: ported verbatim from SqlCommentAnalyzer.java -------------------------------

_IDENT = r'[\w."`\[\]]+'
CREATE_TABLE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(" + _IDENT + r")\s*\(", re.IGNORECASE)
ALTER_ADD_COLUMN = re.compile(
    r"ALTER\s+TABLE\s+(" + _IDENT + r")\s+ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?("
    + _IDENT + r")", re.IGNORECASE)
COMMENT_ON_TABLE = re.compile(r"COMMENT\s+ON\s+TABLE\s+(" + _IDENT + r")\s+IS\s", re.IGNORECASE)
COMMENT_ON_COLUMN = re.compile(r"COMMENT\s+ON\s+COLUMN\s+(" + _IDENT + r")\s+IS\s", re.IGNORECASE)
_FIRST_TOKEN = re.compile(_IDENT)

CONSTRAINT_KEYWORDS = {
    "constraint", "primary", "foreign", "unique", "check", "exclude", "like"}


def strip_identifier(raw: str) -> str:
    """Drop quote characters from each dotted part and lower-case the result."""
    return "".join(c for c in raw if c not in '"`[]').lower()


def last_identifier_part(raw: str) -> str:
    stripped = strip_identifier(raw)
    return stripped.rsplit(".", 1)[-1]


def is_constraint_keyword(raw_token: str) -> bool:
    return strip_identifier(raw_token) in CONSTRAINT_KEYWORDS


def balanced_paren_content(sql: str, open_paren_index: int):
    """Text between the '(' at open_paren_index and its matching ')', honoring nesting and
    single-quoted string literals. None if unbalanced."""
    depth = 0
    in_string = False
    out = []
    for i in range(open_paren_index, len(sql)):
        c = sql[i]
        if in_string:
            out.append(c)
            if c == "'":
                in_string = False
            continue
        if c == "'":
            in_string = True
            out.append(c)
            continue
        if c == "(":
            depth += 1
            if depth == 1:
                continue  # skip the outermost '('
        elif c == ")":
            depth -= 1
            if depth == 0:
                return "".join(out)
        out.append(c)
    return None


def split_top_level(block: str):
    """Split on top-level commas, ignoring commas inside nested parens / string literals."""
    parts = []
    depth = 0
    in_string = False
    current = []
    for c in block:
        if in_string:
            current.append(c)
            if c == "'":
                in_string = False
            continue
        if c == "'":
            in_string = True
            current.append(c)
        elif c == "(":
            depth += 1
            current.append(c)
        elif c == ")":
            depth -= 1
            current.append(c)
        elif c == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(c)
    if current:
        parts.append("".join(current))
    return parts


def sql_created_keys(sql: str):
    keys = []
    seen = set()

    def add(k):
        if k not in seen:
            seen.add(k)
            keys.append(k)

    for m in CREATE_TABLE.finditer(sql):
        table = last_identifier_part(m.group(1))
        add(table)
        block = balanced_paren_content(sql, m.end() - 1)
        if block is None:
            continue
        for col_def in split_top_level(block):
            trimmed = col_def.strip()
            if not trimmed:
                continue
            tok = _FIRST_TOKEN.search(trimmed)
            first = tok.group() if tok else trimmed
            if is_constraint_keyword(first):
                continue
            add(table + "." + last_identifier_part(first))

    for m in ALTER_ADD_COLUMN.finditer(sql):
        if is_constraint_keyword(m.group(2)):
            continue  # ALTER TABLE t ADD CONSTRAINT ...
        table = last_identifier_part(m.group(1))
        add(table + "." + last_identifier_part(m.group(2)))
    return keys


def sql_commented_keys(sql: str):
    keys = set()
    for m in COMMENT_ON_TABLE.finditer(sql):
        keys.add(last_identifier_part(m.group(1)))
    for m in COMMENT_ON_COLUMN.finditer(sql):
        parts = strip_identifier(m.group(1)).split(".")
        if len(parts) >= 2:
            keys.add(parts[-2] + "." + parts[-1])
    return keys


# --- SQL-formatted changelog: split into changesets so the ignore marker can skip one ----------

_CHANGESET_SPLIT = re.compile(r"(?im)^\s*--\s*changeset\b.*$")
_COMMENT_LINE = re.compile(r"(?im)^\s*--\s*comment\b(.*)$")


def sql_missing(content: str, marker: str):
    """Yield missing keys from a SQL-formatted changelog, skipping changesets flagged with the
    marker. The text before the first --changeset header is treated as one anonymous segment."""
    headers = list(_CHANGESET_SPLIT.finditer(content))
    if not headers:
        segments = [content]
    else:
        segments = []
        if headers[0].start() > 0:
            segments.append(content[: headers[0].start()])
        for i, h in enumerate(headers):
            end = headers[i + 1].start() if i + 1 < len(headers) else len(content)
            segments.append(content[h.start():end])

    missing = []
    for seg in segments:
        if any(marker.lower() in c.group(1).lower() for c in _COMMENT_LINE.finditer(seg)):
            continue
        commented = sql_commented_keys(seg)
        for key in sql_created_keys(seg):
            if key not in commented:
                missing.append(key)
    return missing


# --- Structured changelogs (XML / JSON / YAML) -------------------------------------------------

def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _has_remarks(value) -> bool:
    return value is not None and str(value).strip() != ""


def xml_missing(content: str, marker: str):
    root = ElementTree.fromstring(content)
    commented = set()      # objects given remarks inline or via set*Remarks
    created = []           # ordered, deduped
    seen = set()

    def add_created(k):
        if k not in seen:
            seen.add(k)
            created.append(k)

    def walk(elem, in_ignored_changeset):
        tag = _local(elem.tag)
        ignored = in_ignored_changeset
        if tag == "changeSet":
            comment = "".join(
                (c.text or "") for c in elem if _local(c.tag) == "comment")
            attr_comment = elem.get("comment") or ""
            if marker.lower() in (comment + " " + attr_comment).lower():
                ignored = True
        elif tag == "createTable" and not ignored:
            table = last_identifier_part(elem.get("tableName", ""))
            add_created(table)
            if _has_remarks(elem.get("remarks")):
                commented.add(table)
            for col in elem:
                if _local(col.tag) == "column":
                    ck = table + "." + last_identifier_part(col.get("name", ""))
                    add_created(ck)
                    if _has_remarks(col.get("remarks")):
                        commented.add(ck)
        elif tag == "addColumn" and not ignored:
            table = last_identifier_part(elem.get("tableName", ""))
            for col in elem:
                if _local(col.tag) == "column":
                    ck = table + "." + last_identifier_part(col.get("name", ""))
                    add_created(ck)
                    if _has_remarks(col.get("remarks")):
                        commented.add(ck)
        elif tag == "setTableRemarks":
            commented.add(last_identifier_part(elem.get("tableName", "")))
        elif tag == "setColumnRemarks":
            commented.add(last_identifier_part(elem.get("tableName", "")) + "."
                          + last_identifier_part(elem.get("columnName", "")))
        for child in elem:
            walk(child, ignored)

    walk(root, False)
    return [k for k in created if k not in commented]


def _json_walk(node, marker, commented, created, seen, ignored):
    def add_created(k):
        if k not in seen:
            seen.add(k)
            created.append(k)

    if isinstance(node, list):
        for item in node:
            _json_walk(item, marker, commented, created, seen, ignored)
        return
    if not isinstance(node, dict):
        return
    for key, value in node.items():
        local_ignored = ignored
        if key == "changeSet" and isinstance(value, dict):
            comment = str(value.get("comment", ""))
            if marker.lower() in comment.lower():
                local_ignored = True
            _json_walk(value, marker, commented, created, seen, local_ignored)
            continue
        if key == "createTable" and isinstance(value, dict) and not ignored:
            table = last_identifier_part(str(value.get("tableName", "")))
            add_created(table)
            if _has_remarks(value.get("remarks")):
                commented.add(table)
            for col in value.get("columns", []) or []:
                cdef = col.get("column", col) if isinstance(col, dict) else {}
                ck = table + "." + last_identifier_part(str(cdef.get("name", "")))
                add_created(ck)
                if _has_remarks(cdef.get("remarks")):
                    commented.add(ck)
            continue
        if key == "addColumn" and isinstance(value, dict) and not ignored:
            table = last_identifier_part(str(value.get("tableName", "")))
            for col in value.get("columns", []) or []:
                cdef = col.get("column", col) if isinstance(col, dict) else {}
                ck = table + "." + last_identifier_part(str(cdef.get("name", "")))
                add_created(ck)
                if _has_remarks(cdef.get("remarks")):
                    commented.add(ck)
            continue
        if key == "setTableRemarks" and isinstance(value, dict):
            commented.add(last_identifier_part(str(value.get("tableName", ""))))
            continue
        if key == "setColumnRemarks" and isinstance(value, dict):
            commented.add(last_identifier_part(str(value.get("tableName", ""))) + "."
                          + last_identifier_part(str(value.get("columnName", ""))))
            continue
        _json_walk(value, marker, commented, created, seen, local_ignored)


def json_missing(content: str, marker: str):
    commented, created, seen = set(), [], set()
    _json_walk(json.loads(content), marker, commented, created, seen, False)
    return [k for k in created if k not in commented]


def yaml_missing(content: str, marker: str):
    """Best-effort YAML scan (no PyYAML in stdlib). Tracks createTable/addColumn blocks by
    indentation and whether each table/column carries a `remarks:` key. Documented as
    approximate — the agent confirms YAML edits against the file."""
    commented, created, seen = set(), [], set()

    def add_created(k):
        if k not in seen:
            seen.add(k)
            created.append(k)

    lines = content.splitlines()
    cur_table = None
    cur_table_key = None
    pending_col = None  # (key, indent)
    ignore_changeset_indent = None

    def kv(line):
        m = re.match(r"\s*-?\s*(\w+)\s*:\s*(.*)\s*$", line)
        if not m:
            return None, None
        val = m.group(2).strip().strip("'\"")
        return m.group(1), val

    for line in lines:
        indent = len(line) - len(line.lstrip(" "))
        key, val = kv(line)
        if key is None:
            continue
        if ignore_changeset_indent is not None and indent <= ignore_changeset_indent:
            ignore_changeset_indent = None
        if key == "comment" and marker.lower() in (val or "").lower():
            ignore_changeset_indent = indent
        if ignore_changeset_indent is not None:
            continue
        if key in ("createTable", "addColumn"):
            cur_table = None
            cur_table_key = key
        elif key == "tableName":
            cur_table = last_identifier_part(val)
            if cur_table_key == "createTable":
                add_created(cur_table)
        elif key == "name" and cur_table:
            pending_col = cur_table + "." + last_identifier_part(val)
            add_created(pending_col)
        elif key == "remarks":
            if pending_col:
                commented.add(pending_col)
            elif cur_table and cur_table_key == "createTable":
                commented.add(cur_table)
    return [k for k in created if k not in commented]


STRUCTURED = {
    ".xml": xml_missing,
    ".json": json_missing,
    ".yaml": yaml_missing,
    ".yml": yaml_missing,
}


def file_format(path: str) -> str:
    lower = path.lower()
    for ext in STRUCTURED:
        if lower.endswith(ext):
            return ext.lstrip(".")
    if lower.endswith(".sql"):
        return "sql"
    return "other"


def analyze(path: str, content: str, marker: str):
    fmt = file_format(path)
    try:
        if fmt == "sql":
            return sql_missing(content, marker)
        if fmt in ("xml", "json", "yaml", "yml"):
            return STRUCTURED["." + fmt](content, marker)
    except Exception as exc:  # malformed file: report so the agent inspects it manually
        return [f"<parse-error: {exc}>"]
    return []


# --- git plumbing ------------------------------------------------------------------------------

CHANGELOG_EXTS = (".sql", ".xml", ".yaml", ".yml", ".json")


def git(args):
    return subprocess.run(["git"] + args, capture_output=True, text=True, encoding="utf-8")


def staged_files():
    """Return [(status, path)] for staged added/modified files."""
    res = git(["diff", "--cached", "--name-status", "--diff-filter=AM"])
    if res.returncode != 0:
        raise RuntimeError(res.stderr.strip() or "git diff --cached failed")
    out = []
    for line in res.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            out.append((parts[0].strip(), parts[-1].strip()))
    return out


def working_tree_files():
    res = git(["ls-files", "--cached", "--others", "--exclude-standard"])
    if res.returncode != 0:
        raise RuntimeError(res.stderr.strip() or "git ls-files failed")
    return [("?", p.strip()) for p in res.stdout.splitlines() if p.strip()]


def staged_content(path: str) -> str:
    res = git(["show", ":" + path])
    return res.stdout if res.returncode == 0 else ""


def added_lines(path: str) -> str:
    """Concatenated text of staged added ('+') lines for a modified file."""
    res = git(["diff", "--cached", "-U0", "--", path])
    if res.returncode != 0:
        return ""
    return "\n".join(
        ln[1:] for ln in res.stdout.splitlines()
        if ln.startswith("+") and not ln.startswith("+++"))


def looks_like_changelog(path: str, prefix: str) -> bool:
    norm = path.replace("\\", "/")
    if not norm.lower().endswith(CHANGELOG_EXTS):
        return False
    if prefix:
        return prefix.replace("\\", "/") in norm
    return "changelog" in norm.lower() or "/migration" in norm.lower() \
        or "db/" in norm.lower()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--prefix", default="",
                    help="restrict to paths containing this substring (e.g. db/changelog)")
    ap.add_argument("--ignore", default="",
                    help="comma-separated fully-qualified keys to skip (table or table.column)")
    ap.add_argument("--marker", default="no-db-comment",
                    help="changeset-comment marker that opts an object out (default: no-db-comment)")
    ap.add_argument("--all", action="store_true",
                    help="scan the working tree (tracked + untracked) instead of the staged set")
    ap.add_argument("--paths", nargs="*",
                    help="analyze these files directly, bypassing git (uses on-disk content)")
    args = ap.parse_args(argv)

    ignored = {strip_identifier(k) for k in args.ignore.split(",") if k.strip()}

    def is_ignored(key: str) -> bool:
        if key in ignored:
            return True
        if "." in key and key.split(".", 1)[0] in ignored:
            return True
        return False

    try:
        if args.paths:
            entries = [("?", p) for p in args.paths]
        elif args.all:
            entries = working_tree_files()
        else:
            entries = staged_files()
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        print("Run this inside a git repository with staged changes (git add ...).",
              file=sys.stderr)
        return 2

    total_missing = 0
    reported_any = False
    for status, path in entries:
        if not looks_like_changelog(path, args.prefix):
            continue
        if args.paths:
            try:
                with open(path, encoding="utf-8") as fh:
                    content = fh.read()
            except OSError as exc:
                print(f"error: cannot read {path}: {exc}", file=sys.stderr)
                continue
            scope = None
        elif args.all:
            content = staged_content(path) or _read_disk(path)
            scope = None
        else:
            content = staged_content(path)
            # For modified files, only flag objects introduced on staged added lines so
            # pre-existing (legacy) objects in the same file are grandfathered.
            scope = set(sql_created_keys(added_lines(path))) if status == "M" else None

        missing = [k for k in analyze(path, content, args.marker) if not is_ignored(k)]
        if scope is not None:
            missing = [k for k in missing if k in scope or k.startswith("<parse-error")]
        if not missing:
            continue

        reported_any = True
        print(f"FILE: {path}   [format={file_format(path)}, status={status}]")
        for key in missing:
            if key.startswith("<parse-error"):
                print(f"  PARSE ERROR     {key}")
                continue
            kind = "COLUMN" if "." in key else "TABLE "
            print(f"  MISSING {kind}  {key}")
            total_missing += 1
        print()

    if not reported_any:
        print("OK: no staged changelog objects are missing comments.")
        return 0
    print(f"{total_missing} object(s) missing comments across the staged changelogs above.")
    return 1


def _read_disk(path: str) -> str:
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return ""


if __name__ == "__main__":
    sys.exit(main())
