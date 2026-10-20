package io.github.vaneshik.sqlantipatterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.calcite.DataContext;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OptimizationTest {
    private final Analyzer analyzer = new Analyzer();

    @ParameterizedTest
    @ValueSource(strings = {
            "distinct-join", "exists", "without-key", "redundant-distinct", "distinct-group-by",
            "group-filter", "having-count", "null-comparison", "null-filter", "is-null",
            "left-join-filter", "left-join-is-null", "cartesian-product", "join-in-where"
    })
    void generatedSqlPreservesRowsDuplicatesNullsAndColumnNames(String example) throws Exception {
        String sql = Files.readString(Path.of("examples", example + ".sql"));
        Analyzer.Optimization optimized = analyzer.analyze(sql).optimized();
        analyzer.analyze(optimized.sql());
        try (Connection connection = database()) {
            QueryResult before = execute(connection, sql);
            QueryResult after = execute(connection, optimized.sql());
            assertEquals(before.columns(), after.columns(), optimized.sql());
            assertEquals(before.rows().stream().collect(Collectors.groupingBy(row -> row, Collectors.counting())),
                    after.rows().stream().collect(Collectors.groupingBy(row -> row, Collectors.counting())),
                    optimized.sql());
        }
    }

    @Test
    void rewritesDistinctJoinToExistsAndRebuildsTheTree() throws Exception {
        Analyzer.Result result = analyzer.analyze(Files.readString(Path.of("examples/distinct-join.sql")));
        assertEquals("LogicalAggregate", result.tree().type());
        assertTrue(result.optimized().changed());
        assertTrue(result.optimized().sql().contains("EXISTS"), result.optimized().sql());
        assertFalse(result.optimized().sql().contains("DISTINCT"), result.optimized().sql());
        assertTrue(result.optimized().plan().contains("joinType=[semi]"), result.optimized().plan());
        assertFalse(result.optimized().plan().contains("LogicalAggregate"), result.optimized().plan());
        assertTrue(result.optimized().rules().stream().noneMatch(rule -> rule.status() == Analyzer.Status.FOUND));
    }

    @Test
    void retainsDistinctWhenNamesAreNotUnique() throws Exception {
        Analyzer.Optimization optimized = analyzer.analyze(
                Files.readString(Path.of("examples/without-key.sql"))).optimized();
        assertTrue(optimized.plan().contains("LogicalAggregate"), optimized.plan());
        assertFalse(optimized.plan().contains("joinType=[semi]"), optimized.plan());
    }

    @Test
    void movesKeyPredicateBelowAggregateButKeepsAggregatePredicateAboveIt() throws Exception {
        Analyzer.Optimization keyFilter = analyzer.analyze(
                Files.readString(Path.of("examples/group-filter.sql"))).optimized();
        assertTrue(keyFilter.changed());
        assertEquals("LogicalAggregate", keyFilter.tree().type());
        assertEquals("LogicalFilter", keyFilter.tree().inputs().getFirst().type());
        Analyzer.Optimization countFilter = analyzer.analyze(
                Files.readString(Path.of("examples/having-count.sql"))).optimized();
        assertEquals("LogicalFilter", countFilter.tree().type());
        assertEquals("LogicalAggregate", countFilter.tree().inputs().getFirst().type());
    }

    @Test
    void keepsVolatileHavingAboveAggregate() throws Exception {
        Analyzer.Optimization optimized = analyzer.analyze("""
                SELECT customer_id, COUNT(*) AS n
                FROM orders
                GROUP BY customer_id
                HAVING RAND() > 0.5
                """).optimized();
        assertEquals("LogicalFilter", optimized.tree().type(), optimized.plan());
        assertEquals("LogicalAggregate", optimized.tree().inputs().getFirst().type(), optimized.plan());
        assertTrue(optimized.tree().attributes().get("condition").contains("RAND"), optimized.plan());
    }

    @Test
    void doesNotInventAJoinConditionForCartesianProduct() throws Exception {
        Analyzer.Result result = analyzer.analyze(Files.readString(Path.of("examples/cartesian-product.sql")));
        assertFalse(result.optimized().changed());
        assertEquals(result.plan(), result.optimized().plan());
        assertTrue(result.optimized().rules().stream().anyMatch(rule ->
                rule.ruleId().equals("cartesian-product") && rule.status() == Analyzer.Status.FOUND));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT name FROM customer ORDER BY id DESC FETCH NEXT 4 ROWS ONLY",
            "SELECT name AS person, id AS customer_number FROM customer ORDER BY id",
            "SELECT DISTINCT c.id, c.name FROM customer c JOIN orders o ON c.id = o.id ORDER BY c.id",
            "SELECT DISTINCT c.name FROM customer c JOIN orders o ON c.id = o.customer_id ORDER BY c.name",
            "SELECT COUNT(*) AS n FROM orders HAVING FALSE"
    })
    void preservesOutputProjectionOrderingAndEmptyAggregation(String sql) throws Exception {
        Analyzer.Optimization optimized = analyzer.analyze(sql).optimized();
        try (Connection connection = database()) {
            assertEquals(execute(connection, sql), execute(connection, optimized.sql()), optimized.sql());
        }
    }

    private static Connection database() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("unquotedCasing", "TO_LOWER");
        properties.setProperty("quotedCasing", "UNCHANGED");
        Connection connection = DriverManager.getConnection("jdbc:calcite:", properties);
        var schema = connection.unwrap(CalciteConnection.class).getRootSchema().add("public", new AbstractSchema());
        var definitions = DemoSchema.create();
        schema.add("customer", new TestTable(definitions.getTable("customer"), new Object[][] {
                {1, "Ada"}, {2, "Ada"}, {3, null}, {4, "No orders"}, {11, "Large"}, {12, null}
        }));
        schema.add("orders", new TestTable(definitions.getTable("orders"), new Object[][] {
                {1, 2, "paid"}, {101, 1, "paid"}, {102, 1, "paid"}, {103, 2, "paid"},
                {104, 2, null}, {105, 3, "paid"}, {106, null, "paid"},
                {107, 11, "paid"}, {108, 11, "new"}, {109, 12, "paid"}
        }));
        connection.setSchema("public");
        return connection;
    }

    private static QueryResult execute(Connection connection, String sql) throws Exception {
        String statement = sql.strip();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1);
        }
        try (var query = connection.createStatement(); var result = query.executeQuery(statement)) {
            List<String> columns = new ArrayList<>();
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                columns.add(result.getMetaData().getColumnLabel(column));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (result.next()) {
                List<Object> row = new ArrayList<>();
                for (int column = 1; column <= columns.size(); column++) {
                    row.add(result.getObject(column));
                }
                rows.add(row);
            }
            return new QueryResult(columns, rows);
        }
    }

    private record QueryResult(List<String> columns, List<List<Object>> rows) {}

    private static final class TestTable extends AbstractTable implements ScannableTable {
        private final Table definition;
        private final Object[][] rows;

        private TestTable(Table definition, Object[][] rows) {
            this.definition = definition;
            this.rows = rows;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory factory) {
            return definition.getRowType(factory);
        }

        @Override
        public Statistic getStatistic() {
            return definition.getStatistic();
        }

        @Override
        public Enumerable<Object[]> scan(DataContext context) {
            return Linq4j.asEnumerable(rows);
        }
    }
}
