package db.commentcheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import liquibase.change.Change;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;

/**
 * Offline (no JDBC connection, no Docker) checker that reports when a newly added Liquibase changeset
 * creates a table or column without a matching {@code COMMENT ON} statement.
 *
 * <p>Works for structured (XML/YAML/JSON) and SQL-formatted changelogs: every change is rendered to
 * SQL via Liquibase's offline SQL generator and analyzed by {@link SqlCommentAnalyzer}. Created
 * objects are collected only from in-scope (e.g. newly added) changesets; comments are collected from
 * the whole changelog, so a {@code COMMENT ON} placed in a sibling changeset still satisfies the rule.
 */
public final class LiquibaseCommentChecker {

    private static final Logger LOG = Logger.getLogger(LiquibaseCommentChecker.class.getName());

    private final String changeLogPath;
    private final ClassLoader classLoader;
    private final ResourceAccessor resourceAccessor;
    private final String dialect;
    private final Set<String> onlyFiles; // null => every changeset is in scope
    private final String ignoreMarker;
    private final Set<String> ignoredObjects;

    private LiquibaseCommentChecker(Builder b) {
        this.changeLogPath = b.changeLogPath;
        this.classLoader = b.classLoader != null
                ? b.classLoader
                : Thread.currentThread().getContextClassLoader();
        this.resourceAccessor = b.resourceAccessor != null
                ? b.resourceAccessor
                : new ClassLoaderResourceAccessor(this.classLoader);
        this.dialect = b.dialect;
        this.onlyFiles = b.onlyFiles;
        this.ignoreMarker = b.ignoreMarker;
        this.ignoredObjects = b.ignoredObjects;
    }

    /**
     * Builds a checker straight from resolved {@link CommentCheckConfig}, auto-detecting the master
     * changelog when not set explicitly. Returns {@code null} if no changelog can be found (the caller
     * should treat that as "nothing to check").
     */
    public static LiquibaseCommentChecker fromConfig(CommentCheckConfig config, ClassLoader classLoader) {
        String path = config.explicitChangeLog()
                .orElseGet(() -> detectChangeLog(config.changeLogCandidates(), classLoader));
        if (path == null) {
            LOG.warning("Liquibase comment check: no master changelog found (tried "
                    + config.changeLogCandidates() + "); set -D" + CommentCheckConfig.PREFIX
                    + "changeLog=... to point at it. Skipping.");
            return null;
        }

        Set<String> onlyFiles = null;
        if (config.checkOnlyNew()) {
            onlyFiles = GitAddedFiles.since(config.baseRef(), config.changelogPathPrefix());
            if (onlyFiles == null) {
                LOG.warning("Liquibase comment check: could not determine git-added files for base '"
                        + config.baseRef() + "'. Ensure CI fetches the base branch. Skipping (scope=new).");
                onlyFiles = Set.of(); // nothing new -> nothing to enforce, build stays green
            }
        }

        return new Builder(path)
                .classLoader(classLoader)
                .dialect(config.dialect())
                .onlyFiles(onlyFiles)
                .ignoreMarker(config.ignoreMarker())
                .ignoredObjects(config.ignoredObjects())
                .build();
    }

    public CheckResult check() {
        DatabaseChangeLog changeLog = parse();
        Database database = DatabaseFactory.getInstance().getDatabase(dialect);

        // key -> the changeset that created it (first wins, for attribution)
        Map<String, ChangeSet> createdBy = new LinkedHashMap<>();
        Set<String> commented = new LinkedHashSet<>();

        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            String sql = renderSql(changeSet, database);
            // Comments count no matter which changeset they live in.
            commented.addAll(SqlCommentAnalyzer.commentedObjectKeys(sql));
            if (inScope(changeSet)) {
                for (String key : SqlCommentAnalyzer.createdObjectKeys(sql)) {
                    createdBy.putIfAbsent(key, changeSet);
                }
            }
        }

        List<CheckResult.Violation> violations = new ArrayList<>();
        for (Map.Entry<String, ChangeSet> entry : createdBy.entrySet()) {
            String key = entry.getKey();
            if (SqlCommentAnalyzer.isMissing(key, commented, ignoredObjects)) {
                ChangeSet cs = entry.getValue();
                violations.add(new CheckResult.Violation(
                        cs.getFilePath(), cs.getId(), SqlCommentAnalyzer.messageFor(key)));
            }
        }
        return new CheckResult(violations);
    }

    private DatabaseChangeLog parse() {
        try {
            ChangeLogParser parser = ChangeLogParserFactory.getInstance()
                    .getParser(changeLogPath, resourceAccessor);
            return parser.parse(changeLogPath, new ChangeLogParameters(), resourceAccessor);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse changelog: " + changeLogPath, e);
        }
    }

    private boolean inScope(ChangeSet changeSet) {
        if (ignoreMarker != null
                && changeSet.getComments() != null
                && changeSet.getComments().toLowerCase(Locale.ROOT)
                        .contains(ignoreMarker.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (onlyFiles == null) {
            return true;
        }
        return onlyFiles.contains(normalizePath(changeSet.getFilePath()));
    }

    /** Renders every change in the changeset to SQL, falling back to raw text on generator failure. */
    private String renderSql(ChangeSet changeSet, Database database) {
        StringBuilder sb = new StringBuilder();
        for (Change change : changeSet.getChanges()) {
            try {
                for (SqlStatement statement : change.generateStatements(database)) {
                    for (Sql sql : SqlGeneratorFactory.getInstance().generateSql(statement, database)) {
                        sb.append(sql.toSql()).append(";\n");
                    }
                }
            } catch (Exception e) {
                LOG.fine(() -> "Falling back to raw SQL for change in "
                        + changeSet.getFilePath() + " [" + changeSet.getId() + "]: " + e.getMessage());
                sb.append(rawFallback(change)).append(";\n");
            }
        }
        return sb.toString();
    }

    private static String rawFallback(Change change) {
        // RawSQLChange / SQLFileChange expose their text via getSql(); other types may not.
        try {
            var method = change.getClass().getMethod("getSql");
            Object value = method.invoke(change);
            return value != null ? value.toString() : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    private static String detectChangeLog(List<String> candidates, ClassLoader classLoader) {
        ClassLoader cl = classLoader != null ? classLoader : LiquibaseCommentChecker.class.getClassLoader();
        for (String candidate : candidates) {
            if (cl.getResource(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    public static final class Builder {
        private final String changeLogPath;
        private ClassLoader classLoader;
        private ResourceAccessor resourceAccessor;
        private String dialect = "postgresql";
        private Set<String> onlyFiles;
        private String ignoreMarker = "no-db-comment";
        private Set<String> ignoredObjects = Collections.emptySet();

        public Builder(String changeLogPath) {
            this.changeLogPath = changeLogPath;
        }

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder resourceAccessor(ResourceAccessor accessor) {
            this.resourceAccessor = accessor;
            return this;
        }

        public Builder dialect(String dialect) {
            this.dialect = dialect;
            return this;
        }

        /** Restrict the check to changesets whose source file is in this set (e.g. git-added files). */
        public Builder onlyFiles(Set<String> files) {
            this.onlyFiles = files == null
                    ? null
                    : files.stream().map(LiquibaseCommentChecker::normalizePath)
                            .collect(Collectors.toSet());
            return this;
        }

        public Builder ignoreMarker(String marker) {
            this.ignoreMarker = marker;
            return this;
        }

        /** Fully-qualified objects to skip: lower-cased {@code "table"} or {@code "table.column"}. */
        public Builder ignoredObjects(Set<String> objects) {
            this.ignoredObjects = objects.stream()
                    .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            return this;
        }

        public LiquibaseCommentChecker build() {
            return new LiquibaseCommentChecker(this);
        }
    }
}
