package db.commentcheck;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolved configuration for the comment check. Values are read, in order, from:
 * <ol>
 *   <li>JUnit Platform configuration parameters (e.g. {@code junit-platform.properties} or Gradle
 *       {@code systemProperties}),</li>
 *   <li>JVM system properties,</li>
 *   <li>environment variables (key upper-cased, dots/dashes to underscores),</li>
 *   <li>built-in defaults.</li>
 * </ol>
 *
 * <p>All keys are prefixed with {@code liquibase.commentcheck.}. Example overrides:
 * <pre>
 *   -Dliquibase.commentcheck.dialect=oracle
 *   -Dliquibase.commentcheck.changeLog=db/changelog/db.changelog-master.yaml
 *   -Dliquibase.commentcheck.scope=all
 *   LIQUIBASE_COMMENTCHECK_FAILONVIOLATION=false
 * </pre>
 */
public final class CommentCheckConfig {

    public static final String PREFIX = "liquibase.commentcheck.";

    /** Candidate master changelog locations tried (in order) when {@code changeLog} is not set. */
    private static final List<String> DEFAULT_CHANGELOG_CANDIDATES = List.of(
            "db/changelog/db.changelog-master.yaml",
            "db/changelog/db.changelog-master.yml",
            "db/changelog/db.changelog-master.xml",
            "db/changelog/db.changelog-master.json",
            "db/changelog/db.changelog-master.sql");

    private final Function<String, Optional<String>> source;

    private CommentCheckConfig(Function<String, Optional<String>> source) {
        this.source = source;
    }

    /** Builds a config that consults the given platform params first, then system props / env. */
    public static CommentCheckConfig from(Function<String, Optional<String>> platformParams) {
        return new CommentCheckConfig(key -> {
            Optional<String> fromPlatform = platformParams.apply(key).filter(s -> !s.isBlank());
            if (fromPlatform.isPresent()) {
                return fromPlatform;
            }
            String prop = System.getProperty(key);
            if (prop != null && !prop.isBlank()) {
                return Optional.of(prop);
            }
            String envKey = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            String env = System.getenv(envKey);
            return (env != null && !env.isBlank()) ? Optional.of(env) : Optional.empty();
        });
    }

    public boolean enabled() {
        return bool("enabled", true);
    }

    public boolean failOnViolation() {
        return bool("failOnViolation", true);
    }

    /** {@code new} (default) = only git-added changelog files; {@code all} = every changeset. */
    public boolean checkOnlyNew() {
        return !"all".equalsIgnoreCase(string("scope", "new"));
    }

    public Optional<String> explicitChangeLog() {
        return source.apply(PREFIX + "changeLog");
    }

    public List<String> changeLogCandidates() {
        return DEFAULT_CHANGELOG_CANDIDATES;
    }

    public String dialect() {
        return string("dialect", "postgresql");
    }

    public String baseRef() {
        return string("baseRef", GitAddedFiles.baseRefFromEnv());
    }

    public String changelogPathPrefix() {
        return string("changelogPathPrefix", "src/main/resources/db/changelog");
    }

    public String ignoreMarker() {
        return string("ignoreMarker", "no-db-comment");
    }

    public Set<String> ignoredObjects() {
        String raw = string("ignoredObjects", "");
        if (raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String string(String key, String defaultValue) {
        return source.apply(PREFIX + key).orElse(defaultValue);
    }

    private boolean bool(String key, boolean defaultValue) {
        return source.apply(PREFIX + key).map(Boolean::parseBoolean).orElse(defaultValue);
    }
}
