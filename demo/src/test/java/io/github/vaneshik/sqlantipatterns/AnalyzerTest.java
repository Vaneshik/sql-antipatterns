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
import java.util.List;
import java.util.stream.Stream;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.tools.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
        Analyzer.Result analysis = analyzer.analyze(sql);
        Analyzer.RuleResult result = analysis.rules().getFirst();
        assertEquals(FOUND, result.status(), result.explanation() + "\n" + analysis.plan());
        assertTrue(analysis.plan().contains("LogicalAggregate"));
        assertTrue(analysis.plan().contains("LogicalJoin"));
        assertTrue(result.recommendation().contains("EXISTS"));
        PlanNode aggregate = analysis.tree();
        assertEquals("LogicalAggregate", aggregate.type());
        PlanNode project = aggregate.inputs().getFirst();
        assertEquals("LogicalProject", project.type());
        PlanNode input = project.inputs().getFirst();
        PlanNode join = input.type().equals("LogicalFilter") ? input.inputs().getFirst() : input;
        assertEquals("LogicalJoin", join.type());
        assertEquals(input == join
                        ? List.of(aggregate.id(), project.id(), join.id())
                        : List.of(aggregate.id(), project.id(), input.id(), join.id()),
                result.matchedNodeIds());
        assertEquals(2, join.inputs().size());
        assertEquals("[public, customer]", join.inputs().get(0).attributes().get("table"));
        assertEquals("[public, orders]", join.inputs().get(1).attributes().get("table"));
        assertTrue(List.of("=($0, $3)", "=($3, $0)").contains(join.attributes().get("condition")));
        assertTrue(join.inputs().getFirst().fields().getFirst().startsWith("id: INTEGER"));
    }

    @Test
    void keepsDistinctWhenProjectedNamesCanRepeatAcrossCustomers() throws Exception {
        Analyzer.RuleResult result = analyzer.analyze("""
                SELECT DISTINCT c.name
                FROM customer c JOIN orders o ON c.id = o.customer_id
                """).rules().getFirst();
        assertEquals(FOUND, result.status());
        assertEquals("distinct-join-keep-distinct", result.ruleId());
        assertTrue(result.explanation().contains("нет ключа"));
        assertTrue(result.recommendation().contains("Сохраните DISTINCT"));
        assertFalse(result.matchedNodeIds().isEmpty());
    }

    @Test
    void doesNotReportFanOutWhenTheRightJoinColumnIsUnique() throws Exception {
        Analyzer.RuleResult result = analyzer.analyze("""
                SELECT DISTINCT c.id, c.name
                FROM customer c JOIN orders o ON c.id = o.id
                """).rules().getFirst();
        assertEquals(NOT_FOUND, result.status(), result.explanation());
        assertTrue(result.recommendation().isEmpty());
        assertTrue(result.matchedNodeIds().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT c.id, c.name FROM customer c "
                    + "JOIN orders o ON c.id = o.customer_id",
            "SELECT c.id, c.name FROM customer c WHERE EXISTS "
                    + "(SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'paid')"
    })
    void doesNotWarnWithoutDistinct(String sql) throws Exception {
        Analyzer.RuleResult result = analyzer.analyze(sql).rules().getFirst();
        assertEquals(NOT_FOUND, result.status(), result.explanation());
        assertTrue(result.recommendation().isEmpty());
        assertTrue(result.matchedNodeIds().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DISTINCT c.id, o.status FROM customer c JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id, o.status FROM customer c JOIN orders o ON c.id = o.id",
            "SELECT DISTINCT c.id, RAND() FROM customer c JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c LEFT JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c RIGHT JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c FULL JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c CROSS JOIN orders o",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = c.id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON TRUE",
            "SELECT DISTINCT c.id FROM customer c JOIN customer b ON c.id = b.id",
            "SELECT DISTINCT c.id FROM (SELECT * FROM customer) c "
                    + "JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c "
                    + "JOIN (SELECT * FROM orders) o ON c.id = o.customer_id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id LIMIT 1",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id OFFSET 1",
            "SELECT DISTINCT c.id, c.name FROM customer c JOIN orders o ON c.id = o.customer_id ORDER BY c.name LIMIT 2",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id GROUP BY c.id",
            "SELECT c.id FROM customer c JOIN orders o ON c.id = o.customer_id GROUP BY c.id",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id "
                    + "WHERE RAND() > 0.5",
            "SELECT DISTINCT c.id FROM customer c JOIN orders o ON c.id = o.customer_id "
                    + "WHERE o.id IN (SELECT id FROM orders)",
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
        Analyzer.Result analysis = analyzer.analyze(sql);
        Analyzer.RuleResult result = analysis.rules().getFirst();
        assertEquals(UNSUPPORTED, result.status(), result.explanation() + "\n" + analysis.plan());
        assertFalse(result.explanation().isBlank());
        assertTrue(result.recommendation().isEmpty());
        assertTrue(result.matchedNodeIds().isEmpty());
        assertFalse(analysis.tree().type().isBlank());
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
    void meetingExamplesDistinguishRemovingAndKeepingDistinct() throws Exception {
        assertEquals(FOUND, analyzer.analyze(Files.readString(Path.of("examples/distinct-join.sql"))).rules().getFirst().status());
        assertEquals(NOT_FOUND, analyzer.analyze(Files.readString(Path.of("examples/exists.sql"))).rules().getFirst().status());
        Analyzer.RuleResult counterexample = analyzer.analyze(Files.readString(Path.of("examples/without-key.sql")))
                .rules().getFirst();
        assertEquals(FOUND, counterexample.status());
        assertEquals("distinct-join-keep-distinct", counterexample.ruleId());
        assertTrue(counterexample.explanation().contains("нет ключа"));
    }

    @ParameterizedTest
    @CsvSource({
            "without-key, distinct-join-keep-distinct",
            "redundant-distinct, redundant-distinct",
            "distinct-group-by, redundant-distinct",
            "group-filter, group-filter",
            "null-comparison, null-comparison",
            "null-filter, false-filter",
            "left-join-filter, left-join-filter",
            "cartesian-product, cartesian-product"
    })
    void detectsResearchExamplesAndPointsToRealNodes(String example, String ruleId) throws Exception {
        Analyzer.Result result = analyzer.analyze(Files.readString(Path.of("examples", example + ".sql")));
        var matches = result.rules().stream().filter(rule -> rule.status() == FOUND).toList();
        assertEquals(List.of(ruleId), matches.stream().map(Analyzer.RuleResult::ruleId).toList(), result.plan());
        List<Integer> ids = nodes(result.tree()).map(PlanNode::id).toList();
        assertFalse(matches.getFirst().matchedNodeIds().isEmpty());
        assertTrue(ids.containsAll(matches.getFirst().matchedNodeIds()), result.plan());
        assertFalse(matches.getFirst().explanation().isBlank());
        assertFalse(matches.getFirst().recommendation().isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exists", "is-null", "join-in-where", "having-count", "left-join-is-null"})
    void doesNotHighlightCounterexamples(String example) throws Exception {
        Analyzer.Result result = analyzer.analyze(Files.readString(Path.of("examples", example + ".sql")));
        assertTrue(result.rules().stream().noneMatch(rule -> rule.status() == FOUND), result.plan());
    }

    @ParameterizedTest
    @MethodSource("ruleCounterexamples")
    void respectsEachRulesSemanticBoundary(String ruleId, String sql) throws Exception {
        Analyzer.Result result = analyzer.analyze(sql);
        assertTrue(result.rules().stream().noneMatch(rule -> rule.ruleId().equals(ruleId) && rule.status() == FOUND),
                result.plan());
    }

    static Stream<Arguments> ruleCounterexamples() {
        return Stream.of(
                Arguments.of("redundant-distinct", "SELECT DISTINCT name FROM customer"),
                Arguments.of("redundant-distinct",
                        "SELECT DISTINCT COUNT(*) FROM orders GROUP BY customer_id"),
                Arguments.of("group-filter",
                        "SELECT customer_id, COUNT(*) FROM orders GROUP BY customer_id "
                                + "HAVING customer_id > 10 OR COUNT(*) > 1"),
                Arguments.of("group-filter", "SELECT COUNT(*) FROM orders HAVING COUNT(*) > 1"),
                Arguments.of("group-filter",
                        "SELECT customer_id, COUNT(*) FROM orders GROUP BY GROUPING SETS ((customer_id), ()) "
                                + "HAVING customer_id > 10"),
                Arguments.of("left-join-filter",
                        "SELECT c.id FROM customer c LEFT JOIN orders o ON c.id = o.customer_id "
                                + "WHERE o.status = 'paid' OR o.id IS NULL"),
                Arguments.of("left-join-filter",
                        "SELECT c.id FROM customer c LEFT JOIN orders o ON c.id = o.customer_id WHERE c.id > 10"),
                Arguments.of("null-comparison", "SELECT id FROM customer WHERE name = 'NULL'"),
                Arguments.of("null-comparison", "SELECT id FROM customer WHERE name IS NOT DISTINCT FROM NULL"),
                Arguments.of("cartesian-product",
                        "SELECT c.id FROM customer c, orders o WHERE o.customer_id = c.id AND o.status = 'paid'"),
                Arguments.of("cartesian-product",
                        "SELECT x.id FROM (SELECT c.id, o.customer_id FROM customer c, orders o) x "
                                + "WHERE x.id = x.customer_id"));
    }

    @Test
    void preservesNullComparisonAndReportsMoreThanOneMatch() throws Exception {
        Analyzer.Result result = analyzer.analyze("SELECT DISTINCT id, name <> NULL AS missing_name FROM customer");
        var matches = result.rules().stream().filter(rule -> rule.status() == FOUND).toList();
        assertEquals(List.of("redundant-distinct", "null-comparison"),
                matches.stream().map(Analyzer.RuleResult::ruleId).toList(), result.plan());
        PlanNode project = nodes(result.tree()).filter(node -> node.type().equals("LogicalProject")).findFirst().orElseThrow();
        assertTrue(project.attributes().get("missing_name").contains("<>"), result.plan());
        assertTrue(matches.getLast().matchedNodeIds().contains(project.id()));
    }

    private static Stream<PlanNode> nodes(PlanNode node) {
        return Stream.concat(Stream.of(node), node.inputs().stream().flatMap(AnalyzerTest::nodes));
    }
}
