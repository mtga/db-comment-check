package db.commentcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SqlCommentAnalyzerTest {

    @Test
    void createTableWithoutAnyComment_flagsTableAndColumns() {
        String sql = """
                CREATE TABLE customer (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                );
                """;

        List<String> violations = SqlCommentAnalyzer.findMissingComments(sql, Set.of());

        assertThat(violations).contains(
                "missing COMMENT ON TABLE customer",
                "missing COMMENT ON COLUMN customer.id",
                "missing COMMENT ON COLUMN customer.name");
    }

    @Test
    void createTableFullyCommented_passes() {
        String sql = """
                CREATE TABLE customer (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                );
                COMMENT ON TABLE customer IS 'A customer';
                COMMENT ON COLUMN customer.id IS 'PK';
                COMMENT ON COLUMN customer.name IS 'Full name';
                """;

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of())).isEmpty();
    }

    @Test
    void schemaQualifiedAndQuotedIdentifiers_normalizeAndMatch() {
        String sql = """
                CREATE TABLE "public"."Customer" (
                    "Id" BIGINT PRIMARY KEY
                );
                COMMENT ON TABLE public."Customer" IS 'x';
                COMMENT ON COLUMN public."Customer"."Id" IS 'y';
                """;

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of())).isEmpty();
    }

    @Test
    void nestedParensInColumnTypes_doNotBreakColumnParsing() {
        String sql = """
                CREATE TABLE account (
                    id BIGINT,
                    balance NUMERIC(19, 4) NOT NULL,
                    status VARCHAR(10) DEFAULT 'NEW'
                );
                COMMENT ON COLUMN account.id IS 'a';
                COMMENT ON COLUMN account.balance IS 'b';
                """;

        // status is uncommented; the NUMERIC(19,4) comma must not be mistaken for a column boundary
        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of()))
                .containsExactly("missing COMMENT ON COLUMN account.status");
    }

    @Test
    void tableConstraintsAreNotTreatedAsColumns() {
        String sql = """
                CREATE TABLE orders (
                    id BIGINT,
                    CONSTRAINT pk_orders PRIMARY KEY (id),
                    UNIQUE (id)
                );
                COMMENT ON TABLE orders IS 't';
                COMMENT ON COLUMN orders.id IS 'i';
                """;

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of())).isEmpty();
    }

    @Test
    void alterTableAddColumnWithoutComment_isFlagged() {
        String sql = "ALTER TABLE customer ADD COLUMN email VARCHAR(255);";

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of()))
                .containsExactly("missing COMMENT ON COLUMN customer.email");
    }

    @Test
    void alterTableAddConstraint_isNotTreatedAsColumn() {
        String sql = "ALTER TABLE customer ADD CONSTRAINT uq_email UNIQUE (email);";

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of())).isEmpty();
    }

    @Test
    void ignoredObjects_areSkipped() {
        String sql = """
                CREATE TABLE customer_role_link (
                    customer_id BIGINT,
                    role_id BIGINT
                );
                """;

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of("customer_role_link")))
                .isEmpty();
    }

    @Test
    void ignoredSingleColumn_isSkipped() {
        String sql = """
                CREATE TABLE customer (id BIGINT);
                COMMENT ON TABLE customer IS 't';
                """;

        assertThat(SqlCommentAnalyzer.findMissingComments(sql, Set.of("customer.id"))).isEmpty();
    }
}
