package db.commentcheck;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Computes the set of changelog files <em>added</em> on the current branch relative to a base ref,
 * so the comment check only enforces rules on new scripts (legacy files are grandfathered).
 *
 * <p>Returns paths relative to the repo root; strip a resource-root prefix yourself if you load the
 * changelog from the classpath. Returns {@code null} when no base ref is available, signalling the
 * caller to check all changesets (or skip — your choice).
 */
public final class GitAddedFiles {

    private static final Logger LOG = Logger.getLogger(GitAddedFiles.class.getName());

    private GitAddedFiles() {
    }

    /**
     * @param baseRef    e.g. {@code origin/main}
     * @param pathPrefix optional path filter (e.g. {@code src/main/resources/db/changelog});
     *                   pass {@code null} or empty for no filter
     * @return added file paths (repo-relative, forward slashes), or {@code null} if git/base unavailable
     */
    public static Set<String> since(String baseRef, String pathPrefix) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "diff", "--name-only", "--diff-filter=A",
                    baseRef + "...HEAD");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            Set<String> files = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String path = line.trim().replace('\\', '/');
                    if (path.isEmpty()) {
                        continue;
                    }
                    if (pathPrefix == null || pathPrefix.isEmpty() || path.startsWith(pathPrefix)) {
                        files.add(path);
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                LOG.warning("`git diff` exited " + exit + " for base ref '" + baseRef
                        + "'; falling back to checking all changesets.");
                return null;
            }
            return files;
        } catch (Exception e) {
            LOG.warning("Could not determine git-added files (" + e.getMessage()
                    + "); falling back to checking all changesets.");
            return null;
        }
    }

    /** Resolves the base ref from {@code LIQUIBASE_CHECK_BASE_REF}, defaulting to {@code origin/main}. */
    public static String baseRefFromEnv() {
        String fromProp = System.getProperty("liquibase.check.baseRef");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        String fromEnv = System.getenv("LIQUIBASE_CHECK_BASE_REF");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : "origin/main";
    }
}
