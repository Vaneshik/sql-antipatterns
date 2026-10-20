package io.github.vaneshik.sqlantipatterns;

import static io.github.vaneshik.sqlantipatterns.Analyzer.Status.FOUND;
import static io.github.vaneshik.sqlantipatterns.Analyzer.Status.NOT_FOUND;
import static io.github.vaneshik.sqlantipatterns.Analyzer.Status.UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.tools.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalyzerTest {
    private final Analyzer analyzer = new Analyzer();

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DISTINCT c.id, c.name FROM customer c "
                    + "JOIN orders o ON o.customer_id = c.id WHERE o.status = 'paid'",
            "SELECT DISTINCT buyer.name, buyer.id FROM customer buyer "
                    + "INNER JOIN orders purchase ON buyer.id = purchase.customer_id",
            "SELECT DISTINCT c.id AS customer_number FROM customer c "
                    + "JOIN orders o ON c.id = o.customer_id WHERE c.id > 1 AND o.status = 'paid'",
            "SELECT DISTINCT c.id, c.name FROM customer c "
                    + "JOIN orders o ON c.id = o.customer_id WHERE c.name IS NULL",
            "SELECT DISTINCT c.id, c.name FROM customer c "
                    + "JOIN orders o ON c.id = o.customer_id WHERE o.status IS NOT NULL",
            "SELECT DISTINCT \"c\".\"id\", \"c\".\"name\" FROM \"customer\" \"c\" "
                    + "JOIN \"orders\" \"o\" ON \"o\".\"customer_id\" = \"c\".\"id\";",
            "select distinct c.id, c.name from customer c "
                    + "join orders o on o.customer_id = c.id;\n"
    })
    void findsExistenceCheckDespiteSurfaceDifferences(String sql) throws Exception {
        Analyzer.Result result = analyzer.analyze(sql);
        assertEquals(FOUND, result.status(), result.explanation() + "\n" + result.plan());
        assertTrue(result.plan().contains("LogicalAggregate"));
        assertTrue(result.plan().contains("LogicalJoin"));
        assertTrue(result.recommendation().contains("EXISTS"));
    }

    @Test
    void keepsDistinctWhenProjectedNamesCanRepeatAcrossCustomers() throws Exception {
        Analyzer.Result result = analyzer.analyze("""
                SELECT DISTINCT c.name
                FROM customer c JOIN orders o ON c.id = o.customer_id
                """);
        assertEquals(NOT_FOUND, result.status());
        assertTrue(result.explanation().contains("нет ключа"));
        assertTrue(result.recommendation().isEmpty());
    }

    @Test
    void doesNotReportFanOutWhenTheRightJoinColumnIsUnique() throws Exception {
        Analyzer.Result result = analyzer.analyze("""
                SELECT DISTINCT c.id, c.name
                FROM customer c JOIN orders o ON c.id = o.id
                """);
        assertEquals(NOT_FOUND, result.status(), result.explanation());
        assertTrue(result.recommendation().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT c.id, c.name FROM customer c "
                    + "JOIN orders o ON c.id = o.customer_id",
            "SELECT c.id, c.name FROM customer c WHERE EXISTS "
                    + "(SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'paid')"
    })
    void doesNotWarnWithoutDistinct(String sql) throws Exception {
        Analyzer.Result result = analyzer.analyze(sql);
        assertEquals(NOT_FOUND, result.status(), result.explanation());
        assertTrue(result.recommendation().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DISTINCT c.id, o.status FROM customer c JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id, o.status FROM customer c JOIN orders o ON c.id = o.id",
            "SELECT DISTINCT c.id, UPPER(c.name) FROM customer c JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c LEFT JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c RIGHT JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c FULL JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c CROSS JOIN orders o",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id < o.customer_id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = c.id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON TRUE",
            "SELECT DISTINCT c.id FROM customer c JOIN customer b ON c.id = b.id",
            "SELECT DISTINCT c.id FROM (SELECT * FROM customer) c "
                    + "JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c "
                    + "JOIN (SELECT * FROM orders) o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id LIMIT 1",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id OFFSET 1",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id ORDER BY c.id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id GROUP BY c.id",
            "SELECT c.id FROM customer c JOIN orders o ON c.id = o.customer_id GROUP BY c.id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id "
                    + "WHERE o.status = 'paid' OR o.status = 'new'",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id "
                    + "WHERE UPPER(o.status) = 'PAID'",
            "SELECT DISTINCT c.id, ROW_NUMBER() OVER (ORDER BY c.id) FROM customer c "
                    + "JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id "
                    + "JOIN orders other ON c.id = other.customer_id",
            "WITH c AS (SELECT id FROM customer) SELECT DISTINCT c.id "
                    + "FROM c JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id "
                    + "UNION SELECT id FROM customer"
    })
    void distinguishesUnsupportedQueriesFromCheckedNonMatches(String sql) throws Exception {
        Analyzer.Result result = analyzer.analyze(sql);
        assertEquals(UNSUPPORTED, result.status(), result.explanation() + "\n" + result.plan());
        assertFalse(result.explanation().isBlank());
        assertTrue(result.recommendation().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELEC id FROM customer",
            "SELECT FROM",
            "SELECT id FROM customer; SELECT id FROM orders"
    })
    void doesNotHideInvalidSqlAsAnEmptyFinding(String sql) {
        assertThrows(SqlParseException.class, () -> analyzer.analyze(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT missing FROM customer",
            "SELECT id FROM unknown_table"
    })
    void requiresKnownTablesAndColumns(String sql) {
        assertThrows(ValidationException.class, () -> analyzer.analyze(sql));
    }

    @Test
    void meetingExamplesExerciseThreeDifferentOutcomes() throws Exception {
        assertEquals(FOUND, analyzer.analyze(Files.readString(Path.of("examples/distinct-join.sql"))).status());
        assertEquals(NOT_FOUND, analyzer.analyze(Files.readString(Path.of("examples/exists.sql"))).status());
        Analyzer.Result counterexample = analyzer.analyze(Files.readString(Path.of("examples/without-key.sql")));
        assertEquals(NOT_FOUND, counterexample.status());
        assertTrue(counterexample.explanation().contains("нет ключа"));
    }
}
