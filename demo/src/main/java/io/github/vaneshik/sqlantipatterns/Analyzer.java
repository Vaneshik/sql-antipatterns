package io.github.vaneshik.sqlantipatterns;

import java.util.List;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.ImmutableBitSet;

public final class Analyzer {
    public enum Status { FOUND, NOT_FOUND, UNSUPPORTED }

    public record Result(String plan, PlanNode tree, Status status, String explanation,
                         String recommendation, List<Integer> matchedNodeIds) {
        private Result(RelNode rel, Status status, String explanation, String recommendation) {
            this(RelOptUtil.toString(rel), PlanNode.from(rel), status, explanation, recommendation, List.of());
        }
    }

    public Result analyze(String sql)
            throws SqlParseException, ValidationException, RelConversionException {
        String statement = sql.strip();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1);
        }
        SqlParser.Config parserConfig = SqlParser.config()
                .withQuoting(Quoting.DOUBLE_QUOTE)
                .withUnquotedCasing(Casing.TO_LOWER)
                .withQuotedCasing(Casing.UNCHANGED)
                .withCaseSensitive(true);
        SqlNode original = SqlParser.create(statement, parserConfig).parseQuery();
        if (!original.isA(SqlKind.QUERY)) {
            throw new ValidationException("Демо принимает только запросы чтения: SELECT, WITH или VALUES.");
        }
        try (Planner planner = Frameworks.getPlanner(Frameworks.newConfigBuilder()
                .parserConfig(parserConfig)
                .defaultSchema(DemoSchema.create())
                .sqlToRelConverterConfig(SqlToRelConverter.config()
                        .withRelBuilderConfigTransform(config -> config.withAggregateUnique(true)))
                .build())) {
            SqlNode validated = planner.validate(planner.parse(statement));
            RelNode rel = planner.rel(validated).rel;
            if (!(original instanceof SqlSelect select)) {
                return new Result(rel, Status.UNSUPPORTED,
                        "Поддерживается один SELECT без ORDER BY, CTE и операций над множествами.", "");
            }
            if (select.getGroup() != null || select.getHaving() != null
                    || !select.getWindowList().isEmpty()
                    || select.getFetch() != null || select.getOffset() != null) {
                return new Result(rel, Status.UNSUPPORTED,
                        "GROUP BY, HAVING, окна и LIMIT/OFFSET пока не поддерживаются.", "");
            }
            if (!select.isDistinct()) {
                return new Result(rel, Status.NOT_FOUND,
                        "В запросе нет SELECT DISTINCT — правило DISTINCT + JOIN не применяется.", "");
            }
            if (!(select.getFrom() instanceof SqlJoin sqlJoin)
                    || sqlJoin.isNatural()
                    || sqlJoin.getConditionType() != JoinConditionType.ON
                    || !isBaseTable(sqlJoin.getLeft()) || !isBaseTable(sqlJoin.getRight())) {
                return new Result(rel, Status.UNSUPPORTED,
                        "Для этого правила нужен один INNER JOIN базовых таблиц с условием ON.", "");
            }
            if (!(rel instanceof Aggregate aggregate)
                    || !aggregate.getAggCallList().isEmpty()
                    || !(aggregate.getInput() instanceof Project project)) {
                return new Result(rel, Status.UNSUPPORTED,
                        "План не соответствует DISTINCT над простой проекцией.", "");
            }
            RelNode input = project.getInput();
            if (input instanceof Filter filter) {
                if (!simplePredicate(filter.getCondition())) {
                    return new Result(rel, Status.UNSUPPORTED,
                            "Поддерживаются только сравнения колонок и констант, соединённые AND.", "");
                }
                input = filter.getInput();
            }
            if (!(input instanceof Join join)
                    || join.getJoinType() != JoinRelType.INNER
                    || !(join.getLeft() instanceof TableScan left)
                    || !(join.getRight() instanceof TableScan right)
                    || left.getTable().getQualifiedName().equals(right.getTable().getQualifiedName())) {
                return new Result(rel, Status.UNSUPPORTED,
                        "Нужен один INNER JOIN двух разных базовых таблиц.", "");
            }
            int leftWidth = left.getRowType().getFieldCount();
            ImmutableBitSet.Builder rightJoinColumns = ImmutableBitSet.builder();
            for (RexNode condition : RelOptUtil.conjunctions(join.getCondition())) {
                if (!(condition instanceof RexCall call) || call.getKind() != SqlKind.EQUALS
                        || !(call.operands.get(0) instanceof RexInputRef first)
                        || !(call.operands.get(1) instanceof RexInputRef second)
                        || (first.getIndex() < leftWidth) == (second.getIndex() < leftWidth)) {
                    return new Result(rel, Status.UNSUPPORTED,
                            "ON должен сравнивать колонки разных сторон через равенство.", "");
                }
                rightJoinColumns.set(Math.max(first.getIndex(), second.getIndex()) - leftWidth);
            }
            if (rightJoinColumns.isEmpty()) {
                return new Result(rel, Status.UNSUPPORTED,
                        "ON должен сравнивать колонки разных сторон через равенство.", "");
            }
            ImmutableBitSet.Builder projectedColumns = ImmutableBitSet.builder();
            for (RexNode column : project.getProjects()) {
                if (!(column instanceof RexInputRef ref) || ref.getIndex() >= leftWidth) {
                    return new Result(rel, Status.UNSUPPORTED,
                            "В SELECT допускаются только прямые колонки левой таблицы.", "");
                }
                projectedColumns.set(ref.getIndex());
            }
            ImmutableBitSet columns = projectedColumns.build();
            if (!left.getTable().isKey(columns)) {
                return new Result(rel, Status.NOT_FOUND,
                        "В проекции нет ключа левой таблицы. Удаление DISTINCT может изменить "
                                + "число одинаковых строк; совет заменить соединение на EXISTS не выдаётся.", "");
            }
            if (right.getTable().isKey(rightJoinColumns.build())) {
                return new Result(rel, Status.NOT_FOUND,
                        "Соединение идёт по ключу правой таблицы и не размножает строки левой.", "");
            }
            List<Integer> matchedNodeIds = project.getInput() instanceof Filter filter
                    ? List.of(aggregate.getId(), project.getId(), filter.getId(), join.getId())
                    : List.of(aggregate.getId(), project.getId(), join.getId());
            return new Result(RelOptUtil.toString(rel), PlanNode.from(rel), Status.FOUND,
                    "INNER JOIN может размножать строки левой таблицы, а DISTINCT удаляет копии. "
                            + "В результате есть её ключ, колонки правой таблицы не выводятся.",
                    "Рассмотрите WHERE EXISTS с сохранением всех условий ON и WHERE.",
                    matchedNodeIds);
        }
    }

    private static boolean isBaseTable(SqlNode node) {
        if (node.getKind() == SqlKind.AS) {
            node = ((SqlCall) node).operand(0);
        }
        return node instanceof SqlIdentifier;
    }

    private static boolean simplePredicate(RexNode predicate) {
        for (RexNode part : RelOptUtil.conjunctions(predicate)) {
            if (!(part instanceof RexCall call)) {
                return false;
            }
            if (!List.of(SqlKind.EQUALS, SqlKind.NOT_EQUALS, SqlKind.LESS_THAN,
                    SqlKind.LESS_THAN_OR_EQUAL, SqlKind.GREATER_THAN,
                    SqlKind.GREATER_THAN_OR_EQUAL, SqlKind.IS_NULL, SqlKind.IS_NOT_NULL)
                    .contains(call.getKind())) {
                return false;
            }
            for (RexNode operand : call.operands) {
                if (!(operand instanceof RexInputRef) && !(operand instanceof RexLiteral)) {
                    return false;
                }
            }
        }
        return true;
    }
}
