package io.github.vaneshik.sqlantipatterns;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryAdviceTest {
    @Test
    void qualifiesOuterReferencesBeforeMovingThemInsideExists() throws Exception {
        var advice = QueryAdvice.analyze("""
                SELECT DISTINCT c.id, c.name
                FROM customer c JOIN orders o ON o.customer_id = c.id
                WHERE name = 'Alice'
                """, DemoSchema.create());
        assertNotNull(advice.suggestedSql());
        assertTrue(advice.suggestedSql().contains("\"c\".\"name\" = 'Alice'"), advice.suggestedSql());
        assertTrue(advice.suggestedSql().contains("EXISTS"), advice.suggestedSql());
        assertFalse(advice.suggestedSql().contains("DISTINCT"), advice.suggestedSql());
        assertNotEquals(advice.before().plan(), advice.after().plan());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT DISTINCT c.name FROM customer c JOIN orders o ON c.id = o.customer_id",
            "SELECT DISTINCT name FROM customer",
            "SELECT c.id FROM customer c LEFT JOIN orders o ON c.id = o.customer_id WHERE o.id IS NULL",
            "SELECT c.id FROM customer c CROSS JOIN orders o",
            "SELECT id FROM customer WHERE name = NULL",
            "SELECT c.id FROM customer c LEFT JOIN (SELECT b.customer_id FROM orders b "
                    + "LEFT JOIN customer x ON b.customer_id=x.id WHERE x.name='Alice') o ON c.id=o.customer_id",
            "SELECT customer_id, count(*) FROM orders GROUP BY customer_id HAVING count(*) > 1"
    })
    void doesNotChangeSemanticCounterexamples(String sql) throws Exception {
        var advice = QueryAdvice.analyze(sql, DemoSchema.create());
        assertNull(advice.suggestedSql());
        assertNull(advice.after());
    }

    @Test
    void removesOnlyRootDistinctProvedRedundant() throws Exception {
        var advice = QueryAdvice.analyze("SELECT DISTINCT id, name FROM customer", DemoSchema.create());
        assertNotNull(advice.suggestedSql());
        assertFalse(advice.suggestedSql().contains("DISTINCT"));
        assertEquals("LogicalProject", advice.after().tree().type());
    }

    @Test
    void convertsNullRejectingLeftJoinAndRebuildsPlan() throws Exception {
        var advice = QueryAdvice.analyze("""
                SELECT c.id FROM customer c LEFT JOIN orders o ON c.id = o.customer_id
                WHERE o.status = 'paid'
                """, DemoSchema.create());
        assertNotNull(advice.suggestedSql());
        assertFalse(advice.suggestedSql().contains("LEFT JOIN"));
        assertTrue(advice.after().plan().contains("joinType=[inner]"));
    }

    @Test
    void movesGroupPredicateWithoutDroppingAggregation() throws Exception {
        var advice = QueryAdvice.analyze("""
                SELECT customer_id, count(*) FROM orders
                GROUP BY customer_id HAVING customer_id > 10
                """, DemoSchema.create());
        assertNotNull(advice.suggestedSql());
        assertFalse(advice.suggestedSql().contains("HAVING"));
        assertTrue(advice.suggestedSql().contains("GROUP BY"));
        assertTrue(advice.suggestedSql().contains("WHERE"));
    }
}
