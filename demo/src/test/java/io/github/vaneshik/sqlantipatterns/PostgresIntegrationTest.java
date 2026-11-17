package io.github.vaneshik.sqlantipatterns;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "VKR_TEST_JDBC_URL", matches = ".+")
class PostgresIntegrationTest {
    private Connection connection;

    @BeforeEach
    void createSchema() throws Exception {
        connection = DriverManager.getConnection(System.getenv("VKR_TEST_JDBC_URL"));
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE SCHEMA advice_test;
                    SET LOCAL search_path = advice_test;
                    CREATE TABLE customer (id integer PRIMARY KEY, name varchar(100));
                    CREATE TABLE orders (id integer PRIMARY KEY, customer_id integer, status varchar(20));
                    INSERT INTO customer VALUES (1,'Alice'), (2,'Alice'), (3,NULL), (4,'Bob');
                    INSERT INTO orders VALUES (1,1,'paid'), (2,1,'paid'), (3,2,'new'), (4,NULL,'paid'), (5,3,NULL);
                    """);
        }
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DISTINCT c.id, c.name FROM customer c JOIN orders o ON c.id=o.customer_id WHERE o.status='paid'",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id=o.customer_id WHERE name='Alice'",
            "SELECT DISTINCT id, name FROM customer",
            "SELECT c.id, o.status FROM customer c LEFT JOIN orders o ON c.id=o.customer_id WHERE o.status='paid'",
            "SELECT customer_id, count(*) AS total FROM orders GROUP BY customer_id HAVING customer_id > 1",
            "SELECT DISTINCT \"Buyer\".id AS \"Number\" FROM customer \"Buyer\" JOIN orders \"Order\" ON \"Buyer\".id=\"Order\".customer_id",
            "SELECT c.id FROM customer c LEFT JOIN orders o ON c.id=o.customer_id WHERE o.status='paid12345678901234567890'"
    })
    void preservesBagsAndOutputNamesOnPostgres(String sql) throws Exception {
        var advice = QueryAdvice.analyze(sql, PostgresSchema.load(connection, sql));
        assertNotNull(advice.suggestedSql(), advice.before().rules().toString());
        String rewritten = advice.suggestedSql().strip().replaceFirst(";$", "");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT count(*) FROM (
                    ((%s) EXCEPT ALL (%s)) UNION ALL ((%s) EXCEPT ALL (%s))
                ) difference
                """.formatted(sql, rewritten, rewritten, sql))) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), advice.suggestedSql());
        }
        try (var first = connection.prepareStatement(sql); var second = connection.prepareStatement(rewritten)) {
            var before = first.getMetaData();
            var after = second.getMetaData();
            assertEquals(before.getColumnCount(), after.getColumnCount());
            for (int i = 1; i <= before.getColumnCount(); i++) {
                assertEquals(before.getColumnLabel(i), after.getColumnLabel(i));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE uncertain (id integer UNIQUE); INSERT INTO uncertain VALUES (NULL),(NULL)",
            "CREATE TABLE uncertain (id integer NOT NULL); CREATE UNIQUE INDEX subset ON uncertain(id) WHERE id>0; INSERT INTO uncertain VALUES (-1),(-1)",
            "CREATE TABLE uncertain (id integer NOT NULL UNIQUE DEFERRABLE INITIALLY DEFERRED); INSERT INTO uncertain VALUES (1),(1)",
            "CREATE TABLE uncertain (id integer PRIMARY KEY); CREATE TABLE child () INHERITS (uncertain); INSERT INTO uncertain VALUES(1); INSERT INTO child VALUES(1)"
    })
    void doesNotTreatConditionalOrDeferredUniquenessAsBagKey(String ddl) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(ddl);
        }
        String sql = "SELECT DISTINCT id FROM uncertain";
        var advice = QueryAdvice.analyze(sql, PostgresSchema.load(connection, sql));
        assertNull(advice.suggestedSql());
        assertTrue(advice.before().rules().stream().noneMatch(rule -> rule.status() == Analyzer.Status.FOUND));
    }

    @Test
    void resolvesQuotedSchemaAndDroppedColumnPositions() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE advice_test."Mixed Case" (obsolete text, "Key" integer PRIMARY KEY, value text);
                    ALTER TABLE advice_test."Mixed Case" DROP COLUMN obsolete;
                    INSERT INTO advice_test."Mixed Case" VALUES (1,'a'),(2,'a');
                    """);
        }
        String sql = "SELECT DISTINCT \"Key\" FROM advice_test.\"Mixed Case\"";
        var advice = QueryAdvice.analyze(sql, PostgresSchema.load(connection, sql));
        assertNotNull(advice.suggestedSql());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(advice.suggestedSql())) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void loadsOnlyReferencedTablesUsingConnectionSearchPath() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated (payload jsonb); CREATE TABLE customer_shadow (id integer)");
        }
        String sql = "SELECT DISTINCT id FROM customer";
        var schema = PostgresSchema.load(connection, sql);
        assertEquals(java.util.Set.of("customer"), schema.getTableNames());
        assertNotNull(QueryAdvice.analyze(sql, schema).suggestedSql());
    }
}
