package db.commentcheck;

import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

/** The single virtual "test" the engine reports for the Liquibase comment check. */
final class CheckTestDescriptor extends AbstractTestDescriptor {

    static final String SEGMENT_TYPE = "check";
    static final String SEGMENT_VALUE = "liquibase-comments";

    CheckTestDescriptor(UniqueId parentId) {
        super(parentId.append(SEGMENT_TYPE, SEGMENT_VALUE),
                "Newly created tables/columns have COMMENT ON");
    }

    @Override
    public Type getType() {
        return Type.TEST;
    }
}
