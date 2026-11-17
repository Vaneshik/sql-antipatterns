package io.github.vaneshik.sqlantipatterns;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.tools.Frameworks;

public record QueryAdvice(Analyzer.Result before, String suggestedSql, Analyzer.Result after) {
    public static QueryAdvice analyze(String sql, SchemaPlus schema) throws Exception {
        Analyzer analyzer = new Analyzer();
        Analyzer.Result before = analyzer.analyze(sql, schema);
        SqlNode parsed = PostgresSchema.parse(sql);
        if (!(parsed instanceof SqlSelect original)) {
            return new QueryAdvice(before, null, null);
        }
        SqlNode from = original.getFrom();
        if (!Analyzer.isBaseTable(from) && !(from instanceof SqlJoin join
                && Analyzer.isBaseTable(join.getLeft()) && Analyzer.isBaseTable(join.getRight()))) {
            return new QueryAdvice(before, null, null);
        }
        try (var planner = Frameworks.getPlanner(Frameworks.newConfigBuilder().defaultSchema(schema)
                .parserConfig(SqlParser.config().withUnquotedCasing(Casing.TO_LOWER)
                        .withQuotedCasing(Casing.UNCHANGED).withCaseSensitive(true)).build())) {
            String statement = sql.strip();
            if (statement.endsWith(";")) {
                statement = statement.substring(0, statement.length() - 1);
            }
            SqlNode resolved = planner.validate(planner.parse(statement));
            Map<SqlParserPos, SqlIdentifier> bindings = new HashMap<>();
            resolved.accept(new SqlShuttle() {
                @Override
                public SqlNode visit(SqlIdentifier identifier) {
                    bindings.put(identifier.getParserPosition(), identifier);
                    return identifier;
                }
            });
            SqlSelect select = original;
            boolean changed = false;
            for (Analyzer.RuleResult rule : before.rules()) {
                if (rule.status() != Analyzer.Status.FOUND) {
                    continue;
                }
                switch (rule.ruleId()) {
                    case "distinct-join" -> {
                        SqlJoin join = (SqlJoin) select.getFrom();
                        var position = select.getParserPosition();
                        SqlNode condition = qualify(join.getCondition(), bindings);
                        if (select.getWhere() != null) {
                            condition = SqlStdOperatorTable.AND.createCall(position, condition, qualify(select.getWhere(), bindings));
                        }
                        SqlSelect exists = new SqlSelect(position, SqlNodeList.EMPTY,
                                new SqlNodeList(List.of(SqlLiteral.createExactNumeric("1", position)), position),
                                join.getRight(), condition, null, null, SqlNodeList.EMPTY,
                                null, null, null, null, null);
                        select.setFrom(join.getLeft());
                        select.setWhere(SqlStdOperatorTable.EXISTS.createCall(position, exists));
                        removeDistinct(select);
                        changed = true;
                    }
                    case "left-join-filter" -> {
                        if (select.getFrom() instanceof SqlJoin join
                                && join.getJoinType() == JoinType.LEFT) {
                            select.setFrom(new SqlJoin(join.getParserPosition(), join.getLeft(), join.isNaturalNode(),
                                    JoinType.INNER.symbol(join.getParserPosition()), join.getRight(),
                                    join.getConditionTypeNode(), join.getCondition()));
                            changed = true;
                        }
                    }
                    case "group-filter" -> {
                        if (select.getHaving() != null && original.getGroup() != null
                                && Analyzer.isBaseTable(original.getFrom())) {
                            SqlNode having = qualify(select.getHaving(), bindings);
                            select.setWhere(select.getWhere() == null ? having
                                    : SqlStdOperatorTable.AND.createCall(select.getParserPosition(),
                                            select.getWhere(), having));
                            select.setHaving(null);
                            changed = true;
                        }
                    }
                    case "redundant-distinct" -> {
                        if (original.isDistinct() && before.tree().type().equals("LogicalAggregate")
                                && rule.matchedNodeIds().contains(before.tree().id())) {
                            removeDistinct(select);
                            changed = true;
                        }
                    }
                    default -> { }
                }
            }
            if (!changed) {
                return new QueryAdvice(before, null, null);
            }
            String suggested = new SqlPrettyWriter(SqlPrettyWriter.config()
                    .withDialect(PostgresqlSqlDialect.DEFAULT).withQuoteAllIdentifiers(true)
                    .withIndentation(2).withSelectListItemsOnSeparateLines(false)).format(select) + ";";
            Analyzer.Result after = analyzer.analyze(suggested, schema);
            return new QueryAdvice(before, suggested, after);
        }
    }

    private static SqlNode qualify(SqlNode expression, Map<SqlParserPos, SqlIdentifier> bindings) {
        return expression.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlIdentifier identifier) {
                SqlIdentifier resolved = bindings.get(identifier.getParserPosition());
                if (resolved == null) {
                    throw new IllegalArgumentException("Не удалось восстановить привязку колонки — " + identifier);
                }
                return resolved;
            }
        });
    }

    private static void removeDistinct(SqlSelect select) {
        SqlNodeList keywords = (SqlNodeList) select.getOperandList().getFirst();
        select.setOperand(0, new SqlNodeList(keywords.getList().stream()
                .filter(node -> ((SqlLiteral) node).symbolValue(SqlSelectKeyword.class) != SqlSelectKeyword.DISTINCT)
                .toList(), keywords.getParserPosition()));
    }
}
