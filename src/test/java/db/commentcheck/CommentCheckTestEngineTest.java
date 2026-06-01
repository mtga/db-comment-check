package db.commentcheck;

import static org.junit.platform.testkit.engine.EventConditions.finishedSuccessfully;
import static org.junit.platform.testkit.engine.EventConditions.test;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Drives the engine end-to-end via {@link EngineTestKit} against fixture changelogs, proving it
 * auto-contributes a test that passes / fails correctly. {@code scope=all} so the fixtures are in
 * scope without needing a git repository; {@code dialect=h2} renders DDL offline.
 */
class CommentCheckTestEngineTest {

    private EngineTestKit.Builder engine(String changeLog) {
        return EngineTestKit.engine(CommentCheckTestEngine.ENGINE_ID)
                .configurationParameter(CommentCheckConfig.PREFIX + "enabled", "true")
                .configurationParameter(CommentCheckConfig.PREFIX + "scope", "all")
                .configurationParameter(CommentCheckConfig.PREFIX + "dialect", "h2")
                .configurationParameter(CommentCheckConfig.PREFIX + "changeLog", changeLog);
    }

    @Test
    void failsWhenCreatedTableHasNoComment() {
        engine("db/changelog/test-missing.xml")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).failed(1));
    }

    @Test
    void passesWhenCommentsExistInASeparateChangeset() {
        engine("db/changelog/test-ok.xml")
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, test().and(finishedSuccessfully()));
    }

    @Test
    void skipsWhenDisabled() {
        EngineTestKit.engine(CommentCheckTestEngine.ENGINE_ID)
                .configurationParameter(CommentCheckConfig.PREFIX + "enabled", "false")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.skipped(1).started(0));
    }
}
