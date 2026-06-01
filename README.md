# db-comment-check

A **DB-free, Docker-free** library that fails the build when a *newly added* Liquibase changeset
creates a table or column without a matching `COMMENT ON TABLE` / `COMMENT ON COLUMN` statement.

It ships as a **JUnit Platform `TestEngine`**, so consumers just add it as a `testImplementation`
dependency and the check **runs automatically on `gradle test` / `gradle check`** — no test class to
copy into each service. Works for Spring Boot apps and for both **structured (XML/YAML/JSON)** and
**SQL-formatted** changelogs (every change is rendered to SQL offline, then analyzed as text).

> Reference implementation generated from the approved plan. It has **not** been compiled in the
> environment where it was written — build it with `gradle test` and adjust the `liquibase-core` /
> JUnit versions and package name to match your repos.

## How a service turns it on

```kotlin
dependencies {
    testImplementation("db.commentcheck:db-comment-check:0.1.0")
}

tasks.test {
    useJUnitPlatform() // Spring Boot's spring-boot-starter-test already does this
}
```

That's it. On the next `gradle test`, JUnit Platform discovers the engine via
`META-INF/services/org.junit.platform.engine.TestEngine` and runs one virtual test:
*"Newly created tables/columns have COMMENT ON"*.

### Zero-config defaults
- **Changelog**: auto-detected from the test classpath, trying
  `db/changelog/db.changelog-master.{yaml,yml,xml,json,sql}` (Spring Boot's default location).
- **Scope**: only changelog **files added** on the current branch vs `origin/main` — legacy tables are
  grandfathered.
- **Dialect**: `postgresql`. **Fail on violation**: yes.

### Configuration (all keys prefixed `liquibase.commentcheck.`)
Set via Gradle `systemProperty(...)`, a `junit-platform.properties` on the test classpath, or env vars
(`LIQUIBASE_COMMENTCHECK_*`):

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Turn the check off entirely. |
| `failOnViolation` | `true` | `false` = log violations as a warning but pass (soft rollout). |
| `scope` | `new` | `new` = git-added files only; `all` = every changeset. |
| `changeLog` | auto-detect | Explicit master changelog path on the classpath. |
| `dialect` | `postgresql` | Liquibase DB short name used for offline SQL rendering (`oracle`, `h2`, …). |
| `baseRef` | `origin/main` | Git base for the "added files" diff (also `LIQUIBASE_CHECK_BASE_REF`). |
| `changelogPathPrefix` | `src/main/resources/db/changelog` | Path filter for the git diff. |
| `ignoreMarker` | `no-db-comment` | Token in a changeset comment to skip it. |
| `ignoredObjects` | _(empty)_ | Comma-separated `table` / `table.column` keys to skip. |

Example (`build.gradle.kts`):
```kotlin
tasks.test {
    systemProperty("liquibase.commentcheck.dialect", "oracle")
    systemProperty("liquibase.commentcheck.failOnViolation", "false") // warn-only first release
}
```

### CI note
"new files" scoping needs the base branch present: use `fetch-depth: 0` (or `git fetch origin main`)
so `git diff --diff-filter=A origin/main...HEAD` resolves. If it can't be computed the check skips
(build stays green) and logs a warning.

## Opting out intentionally
- Add the marker (default `no-db-comment`) to a changeset's `<comment>` / `--comment`.
- Or set `liquibase.commentcheck.ignoredObjects=join_table,customer.legacy_col`.

## Layout
```
src/main/java/db/commentcheck/
  SqlCommentAnalyzer.java        # pure SQL-text analysis (created vs commented sets) — fully unit tested
  LiquibaseCommentChecker.java   # parses changelog offline, renders SQL, aggregates, applies analyzer
  GitAddedFiles.java             # git diff --diff-filter=A <base>...HEAD -> set of new files
  CommentCheckConfig.java        # resolves config from platform params / sysprops / env
  CommentCheckTestEngine.java    # JUnit Platform engine (auto-discovered)
  CheckTestDescriptor.java       # the single virtual test
  CheckResult.java               # result + human-readable report
src/main/resources/META-INF/services/org.junit.platform.engine.TestEngine
src/test/java/db/commentcheck/   # analyzer tests + EngineTestKit end-to-end tests
```

## Manual invocation (optional)
If you ever want to call it directly instead of via the auto-engine:
```java
CheckResult result = new LiquibaseCommentChecker.Builder("db/changelog/db.changelog-master.yaml")
        .dialect("postgresql")
        .onlyFiles(GitAddedFiles.since("origin/main", "src/main/resources/db/changelog"))
        .build()
        .check();
if (!result.isClean()) throw new AssertionError(result.describe());
```

## Known limitation
File-level scoping won't catch changesets *appended* to an existing changelog file. If your convention
allows that, switch `GitAddedFiles` to line-level diff analysis (only `+` lines).
