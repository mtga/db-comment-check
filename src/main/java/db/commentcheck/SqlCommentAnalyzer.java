package db.commentcheck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free analysis of a blob of SQL text.
 *
 * <p>It extracts the set of created tables/columns and the set of tables/columns that have a
 * {@code COMMENT ON ...} statement. Callers compare the two sets to find created objects missing a
 * comment.
 *
 * <p>Object keys are normalized: a table is {@code "table"}; a column is {@code "table.column"}.
 * Identifiers are unquoted, schema-stripped and lower-cased, so {@code "public"."My_Table"} and
 * {@code my_table} compare equal. The analyzer is dialect-tolerant (Postgres / Oracle / H2 share the
 * {@code COMMENT ON TABLE|COLUMN ... IS '...'} syntax).
 */
public final class SqlCommentAnalyzer {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "ALTER\\s+TABLE\\s+([\\w.\"`\\[\\]]+)\\s+ADD\\s+(?:COLUMN\\s+)?"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT_ON_TABLE = Pattern.compile(
            "COMMENT\\s+ON\\s+TABLE\\s+([\\w.\"`\\[\\]]+)\\s+IS\\s",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT_ON_COLUMN = Pattern.compile(
            "COMMENT\\s+ON\\s+COLUMN\\s+([\\w.\"`\\[\\]]+)\\s+IS\\s",
            Pattern.CASE_INSENSITIVE);

    /** Keywords that introduce a constraint rather than a column. */
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "constraint", "primary", "foreign", "unique", "check", "exclude", "like");

    private SqlCommentAnalyzer() {
    }

    /** Keys of objects created in the SQL: {@code "table"} and {@code "table.column"}. */
    public static Set<String> createdObjectKeys(String sql) {
        Set<String> keys = new LinkedHashSet<>();

        Matcher create = CREATE_TABLE.matcher(sql);
        while (create.find()) {
            String table = lastIdentifierPart(create.group(1));
            keys.add(table);

            String columnsBlock = balancedParenContent(sql, create.end() - 1);
            if (columnsBlock == null) {
                continue;
            }
            for (String columnDef : splitTopLevel(columnsBlock)) {
                String trimmed = columnDef.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String firstToken = firstToken(trimmed);
                if (isConstraintKeyword(firstToken)) {
                    continue;
                }
                keys.add(table + "." + lastIdentifierPart(firstToken));
            }
        }

        Matcher alter = ALTER_ADD_COLUMN.matcher(sql);
        while (alter.find()) {
            if (isConstraintKeyword(alter.group(2))) {
                continue; // e.g. "ALTER TABLE t ADD CONSTRAINT ..."
            }
            String table = lastIdentifierPart(alter.group(1));
            keys.add(table + "." + lastIdentifierPart(alter.group(2)));
        }
        return keys;
    }

    /** Keys of objects with a {@code COMMENT ON}: {@code "table"} and {@code "table.column"}. */
    public static Set<String> commentedObjectKeys(String sql) {
        Set<String> keys = new LinkedHashSet<>();

        Matcher table = COMMENT_ON_TABLE.matcher(sql);
        while (table.find()) {
            keys.add(lastIdentifierPart(table.group(1)));
        }
        Matcher column = COMMENT_ON_COLUMN.matcher(sql);
        while (column.find()) {
            // COMMENT ON COLUMN <schema?>.<table>.<col>
            String[] parts = stripIdentifier(column.group(1)).split("\\.");
            if (parts.length >= 2) {
                keys.add(parts[parts.length - 2] + "." + parts[parts.length - 1]);
            }
        }
        return keys;
    }

    /**
     * Convenience for single-blob analysis (used by unit tests). Returns human-readable messages for
     * every created object lacking a comment.
     *
     * @param ignoredQualifiedKeys lower-cased keys to ignore: {@code "table"} or {@code "table.column"}
     */
    public static List<String> findMissingComments(String sql, Set<String> ignoredQualifiedKeys) {
        Set<String> commented = commentedObjectKeys(sql);
        List<String> violations = new ArrayList<>();
        for (String key : createdObjectKeys(sql)) {
            if (isMissing(key, commented, ignoredQualifiedKeys)) {
                violations.add(messageFor(key));
            }
        }
        return violations;
    }

    /** True if {@code key} was created, is uncommented, and not ignored (directly or via its table). */
    public static boolean isMissing(String key, Set<String> commented, Set<String> ignored) {
        if (commented.contains(key) || ignored.contains(key)) {
            return false;
        }
        int dot = key.indexOf('.');
        if (dot >= 0 && ignored.contains(key.substring(0, dot))) {
            return false; // whole table is ignored
        }
        return true;
    }

    public static String messageFor(String key) {
        return key.indexOf('.') >= 0
                ? "missing COMMENT ON COLUMN " + key
                : "missing COMMENT ON TABLE " + key;
    }

    private static boolean isConstraintKeyword(String rawToken) {
        return CONSTRAINT_KEYWORDS.contains(stripIdentifier(rawToken).toLowerCase(Locale.ROOT));
    }

    /**
     * Given the index of an opening '(', returns the text between it and its matching ')',
     * respecting nesting and single-quoted string literals. Returns {@code null} if unbalanced.
     */
    private static String balancedParenContent(String sql, int openParenIndex) {
        int depth = 0;
        boolean inString = false;
        StringBuilder sb = new StringBuilder();
        for (int i = openParenIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
                if (depth == 1) {
                    continue; // skip the outermost '('
                }
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sb.toString();
                }
            }
            sb.append(c);
        }
        return null;
    }

    /** Splits on top-level commas (ignores commas inside nested parens / string literals). */
    private static List<String> splitTopLevel(String block) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '\'' -> {
                    inString = true;
                    current.append(c);
                }
                case '(' -> {
                    depth++;
                    current.append(c);
                }
                case ')' -> {
                    depth--;
                    current.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        parts.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                default -> current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String firstToken(String definition) {
        Matcher m = Pattern.compile("[\\w.\"`\\[\\]]+").matcher(definition);
        return m.find() ? m.group() : definition;
    }

    /** Returns the last dot-separated component, normalized (unquoted, lower-cased). */
    private static String lastIdentifierPart(String raw) {
        String stripped = stripIdentifier(raw);
        int dot = stripped.lastIndexOf('.');
        return dot >= 0 ? stripped.substring(dot + 1) : stripped;
    }

    /** Removes quote characters from each dotted part and lower-cases the result. */
    private static String stripIdentifier(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
