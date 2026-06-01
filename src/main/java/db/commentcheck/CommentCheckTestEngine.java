package db.commentcheck;

import java.util.Optional;
import java.util.logging.Logger;

import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * A JUnit Platform {@link TestEngine} that runs the Liquibase comment check as a virtual test.
 *
 * <p>Because it is registered via {@code META-INF/services/org.junit.platform.engine.TestEngine},
 * JUnit Platform discovers and runs it automatically whenever this library is on the test classpath
 * (e.g. added through {@code testImplementation}). Consumers need no test class of their own.
 *
 * <p>The engine ignores discovery selectors and always contributes its single check, so it runs even
 * when the consuming module has no other tests. Configure it via {@link CommentCheckConfig} keys.
 */
public final class CommentCheckTestEngine implements TestEngine {

    private static final Logger LOG = Logger.getLogger(CommentCheckTestEngine.class.getName());

    public static final String ENGINE_ID = "liquibase-comment-check";

    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    public Optional<String> getGroupId() {
        return Optional.of("db.commentcheck");
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineDescriptor root = new EngineDescriptor(uniqueId, "Liquibase comment check");
        root.addChild(new CheckTestDescriptor(uniqueId));
        return root;
    }

    @Override
    public void execute(ExecutionRequest request) {
        TestDescriptor root = request.getRootTestDescriptor();
        var listener = request.getEngineExecutionListener();
        CommentCheckConfig config = CommentCheckConfig.from(
                key -> request.getConfigurationParameters().get(key));

        listener.executionStarted(root);
        for (TestDescriptor child : root.getChildren()) {
            if (!config.enabled()) {
                listener.executionSkipped(child,
                        "disabled via " + CommentCheckConfig.PREFIX + "enabled=false");
                continue;
            }
            listener.executionStarted(child);
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                LiquibaseCommentChecker checker = LiquibaseCommentChecker.fromConfig(config, cl);
                CheckResult result = checker == null ? CheckResult.clean() : checker.check();

                if (result.isClean()) {
                    listener.executionFinished(child, TestExecutionResult.successful());
                } else if (!config.failOnViolation()) {
                    LOG.warning("Liquibase comment check (warn-only):\n" + result.describe());
                    listener.executionFinished(child, TestExecutionResult.successful());
                } else {
                    listener.executionFinished(child,
                            TestExecutionResult.failed(new AssertionError(result.describe())));
                }
            } catch (Throwable t) {
                listener.executionFinished(child, TestExecutionResult.failed(t));
            }
        }
        listener.executionFinished(root, TestExecutionResult.successful());
    }
}
