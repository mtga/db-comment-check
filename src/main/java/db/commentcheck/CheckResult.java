package db.commentcheck;

import java.util.List;

/** Outcome of a {@link LiquibaseCommentChecker} run. */
public record CheckResult(List<Violation> violations) {

    /** A result with no violations (e.g. nothing in scope to check). */
    public static CheckResult clean() {
        return new CheckResult(List.of());
    }

    public boolean isClean() {
        return violations.isEmpty();
    }

    public String describe() {
        if (isClean()) {
            return "All newly created tables/columns have a COMMENT ON.";
        }
        StringBuilder sb = new StringBuilder(
                violations.size() + " Liquibase comment violation(s):\n");
        for (Violation v : violations) {
            sb.append("  - ").append(v.changeSetFile()).append(" [").append(v.changeSetId())
                    .append("]: ").append(v.message()).append('\n');
        }
        sb.append("\nAdd a `COMMENT ON TABLE ...` / `COMMENT ON COLUMN ...` for each, or mark the\n")
                .append("changeset with the ignore marker if the omission is intentional.");
        return sb.toString();
    }

    /** A single missing-comment finding tied to the originating changeset. */
    public record Violation(String changeSetFile, String changeSetId, String message) {
    }
}
