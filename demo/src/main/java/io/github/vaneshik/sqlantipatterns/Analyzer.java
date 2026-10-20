package io.github.vaneshik.sqlantipatterns;

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.Strong;
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
import org.apache.calcite.rex.RexUtil;
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

    public record Result(String plan, PlanNode tree, List<RuleResult> rules) {}

    public record RuleResult(String ruleId, String title, String pattern, Status status,
                             String explanation, String recommendation, List<Integer> matchedNodeIds) {
        private RuleResult(Status status, String explanation, String recommendation) {
            this("distinct-join", "DISTINCT после JOIN", "Aggregate → Project → [Filter] → Join",
                    status, explanation, recommendation, List.of());
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
                        .withRelBuilderConfigTransform(config -> config.withAggregateUnique(true).withSimplify(false)))
                .build())) {
            SqlNode validated = planner.validate(planner.parse(statement));
            RelNode rel = planner.rel(validated).rel;
            List<RuleResult> rules = new ArrayList<>();
            rules.add(distinctJoin(original, rel));
            inspect(rel, rules);
            inspectCartesianProduct(rel, rules);
            return new Result(RelOptUtil.toString(rel), PlanNode.from(rel), List.copyOf(rules));
        }
    }

    private static RuleResult distinctJoin(SqlNode original, RelNode rel) {
        if (!(original instanceof SqlSelect select)) {
            return new RuleResult(Status.UNSUPPORTED,
                    "Поддерживается один SELECT без ORDER BY, CTE и операций над множествами.", "");
        }
        if (select.getGroup() != null || select.getHaving() != null
                || !select.getWindowList().isEmpty()
                || select.getFetch() != null || select.getOffset() != null) {
            return new RuleResult(Status.UNSUPPORTED,
                    "GROUP BY, HAVING, окна и LIMIT/OFFSET пока не поддерживаются.", "");
        }
        if (!select.isDistinct()) {
            return new RuleResult(Status.NOT_FOUND, "", "");
        }
        if (!(select.getFrom() instanceof SqlJoin sqlJoin)
                || sqlJoin.isNatural()
                || sqlJoin.getConditionType() != JoinConditionType.ON
                || !isBaseTable(sqlJoin.getLeft()) || !isBaseTable(sqlJoin.getRight())) {
            return new RuleResult(Status.UNSUPPORTED,
                    "Для этого правила нужен один INNER JOIN базовых таблиц с условием ON.", "");
        }
        if (!(rel instanceof Aggregate aggregate)
                || !aggregate.getAggCallList().isEmpty()
                || !(aggregate.getInput() instanceof Project project)) {
            return new RuleResult(Status.UNSUPPORTED,
                    "План не соответствует DISTINCT над простой проекцией.", "");
        }
        RelNode input = project.getInput();
        if (input instanceof Filter filter) {
            if (!simplePredicate(filter.getCondition())) {
                return new RuleResult(Status.UNSUPPORTED,
                        "Поддерживаются только сравнения колонок и констант, соединённые AND.", "");
            }
            input = filter.getInput();
        }
        if (!(input instanceof Join join)
                || join.getJoinType() != JoinRelType.INNER
                || !(join.getLeft() instanceof TableScan left)
                || !(join.getRight() instanceof TableScan right)
                || left.getTable().getQualifiedName().equals(right.getTable().getQualifiedName())) {
            return new RuleResult(Status.UNSUPPORTED,
                    "Нужен один INNER JOIN двух разных базовых таблиц.", "");
        }
        int leftWidth = left.getRowType().getFieldCount();
        ImmutableBitSet.Builder rightJoinColumns = ImmutableBitSet.builder();
        for (RexNode condition : RelOptUtil.conjunctions(join.getCondition())) {
            if (!(condition instanceof RexCall call) || call.getKind() != SqlKind.EQUALS
                    || !(call.operands.get(0) instanceof RexInputRef first)
                    || !(call.operands.get(1) instanceof RexInputRef second)
                    || (first.getIndex() < leftWidth) == (second.getIndex() < leftWidth)) {
                return new RuleResult(Status.UNSUPPORTED,
                        "ON должен сравнивать колонки разных сторон через равенство.", "");
            }
            rightJoinColumns.set(Math.max(first.getIndex(), second.getIndex()) - leftWidth);
        }
        if (rightJoinColumns.isEmpty()) {
            return new RuleResult(Status.UNSUPPORTED,
                    "ON должен сравнивать колонки разных сторон через равенство.", "");
        }
        ImmutableBitSet.Builder projectedColumns = ImmutableBitSet.builder();
        for (RexNode column : project.getProjects()) {
            if (!(column instanceof RexInputRef ref) || ref.getIndex() >= leftWidth) {
                return new RuleResult(Status.UNSUPPORTED,
                        "В SELECT допускаются только прямые колонки левой таблицы.", "");
            }
            projectedColumns.set(ref.getIndex());
        }
        ImmutableBitSet columns = projectedColumns.build();
        if (!left.getTable().isKey(columns)) {
            return new RuleResult(Status.NOT_FOUND,
                    "В проекции нет ключа левой таблицы. Удаление DISTINCT может изменить "
                            + "число одинаковых строк; совет заменить соединение на EXISTS не выдаётся.", "");
        }
        if (right.getTable().isKey(rightJoinColumns.build())) {
            return new RuleResult(Status.NOT_FOUND,
                    "Соединение идёт по ключу правой таблицы и не размножает строки левой.", "");
        }
        List<Integer> matchedNodeIds = project.getInput() instanceof Filter filter
                ? List.of(aggregate.getId(), project.getId(), filter.getId(), join.getId())
                : List.of(aggregate.getId(), project.getId(), join.getId());
        return new RuleResult("distinct-join", "DISTINCT после JOIN",
                "Aggregate → Project → [Filter] → Join", Status.FOUND,
                "INNER JOIN может размножать строки левой таблицы, а DISTINCT удаляет копии. "
                        + "В результате есть её ключ, колонки правой таблицы не выводятся.",
                "Рассмотрите WHERE EXISTS с сохранением всех условий ON и WHERE.",
                matchedNodeIds);
    }

    private static void inspect(RelNode node, List<RuleResult> rules) {
        if (node instanceof Aggregate aggregate
                && aggregate.getGroupType() == Aggregate.Group.SIMPLE
                && aggregate.getGroupCount() > 0
                && aggregate.getAggCallList().isEmpty()
                && aggregate.getGroupSet().equals(ImmutableBitSet.range(aggregate.getInput().getRowType().getFieldCount()))
                && Boolean.TRUE.equals(node.getCluster().getMetadataQuery()
                        .areColumnsUnique(aggregate.getInput(), aggregate.getGroupSet()))) {
            rules.add(matched("redundant-distinct", "Избыточный DISTINCT / GROUP BY", "Aggregate → уникальный вход",
                    "Вход уже уникален по выводимым колонкам. Повторное устранение дубликатов ничего не меняет.",
                    "Удалите избыточный DISTINCT или GROUP BY без агрегатных функций.", aggregate, aggregate.getInput()));
        }
        if (node instanceof Filter filter) {
            if (filter.getCondition().isAlwaysFalse()) {
                rules.add(matched("false-filter", "Всегда ложный фильтр", "Filter(false)",
                        "Условие фильтра всегда ложно: через этот узел не проходит ни одной строки.",
                        "Проверьте условие WHERE. Если пустой результат нужен намеренно, исправление не требуется.", filter));
            }
            if (filter.getInput() instanceof Aggregate aggregate
                    && aggregate.getGroupType() == Aggregate.Group.SIMPLE
                    && aggregate.getGroupCount() > 0
                    && RexUtil.isDeterministic(filter.getCondition())) {
                ImmutableBitSet columns = RelOptUtil.InputFinder.bits(filter.getCondition());
                if (!columns.isEmpty() && ImmutableBitSet.range(aggregate.getGroupCount()).contains(columns)) {
                    rules.add(matched("group-filter", "Фильтр после группировки", "Filter → Aggregate",
                            "Условие зависит только от ключей группировки и может отсеивать строки до агрегации.",
                            "Перенесите это условие из HAVING в WHERE.", filter, aggregate));
                }
            }
            if (filter.getInput() instanceof Join join && join.getJoinType() == JoinRelType.LEFT
                    && RexUtil.isDeterministic(filter.getCondition())
                    && Strong.isNotTrue(filter.getCondition(), ImmutableBitSet.range(
                            join.getLeft().getRowType().getFieldCount(), join.getRowType().getFieldCount()))) {
                rules.add(matched("left-join-filter", "Фильтр после LEFT JOIN", "Filter → Left Join",
                        "Фильтр отбрасывает строки без совпадения справа. LEFT JOIN здесь ведёт себя как INNER JOIN.",
                        "Используйте INNER JOIN, сохранив фильтр в WHERE.", filter, join));
            }
        }
        List<RexNode> expressions = switch (node) {
            case Filter filter -> List.of(filter.getCondition());
            case Project project -> project.getProjects();
            case Join join -> List.of(join.getCondition());
            default -> List.of();
        };
        if (expressions.stream().anyMatch(Analyzer::containsNullComparison)) {
            rules.add(matched("null-comparison", "Сравнение с NULL", "Выражение = NULL или <> NULL",
                    "Обычное сравнение с NULL возвращает UNKNOWN, а не TRUE или FALSE.",
                    "Для проверки отсутствующего значения используйте IS NULL или IS NOT NULL.", node));
        }
        for (RelNode input : node.getInputs()) {
            inspect(input, rules);
        }
    }

    private static void inspectCartesianProduct(RelNode node, List<RuleResult> rules) {
        List<RexNode> predicates = new ArrayList<>();
        while (node instanceof Project || node instanceof Filter) {
            if (node instanceof Project project) {
                predicates = new ArrayList<>(RelOptUtil.pushPastProject(predicates, project));
                node = project.getInput();
            } else if (node instanceof Filter filter) {
                predicates.addAll(RelOptUtil.conjunctions(filter.getCondition()));
                node = filter.getInput();
            }
        }
        if (!(node instanceof Join join) || join.getJoinType() != JoinRelType.INNER
                || !join.getCondition().isAlwaysTrue()
                || !(join.getLeft() instanceof TableScan) || !(join.getRight() instanceof TableScan)) {
            return;
        }
        int leftWidth = join.getLeft().getRowType().getFieldCount();
        for (RexNode predicate : predicates) {
            ImmutableBitSet columns = RelOptUtil.InputFinder.bits(predicate);
            if (columns.intersects(ImmutableBitSet.range(leftWidth))
                    && columns.intersects(ImmutableBitSet.range(leftWidth, join.getRowType().getFieldCount()))) {
                return;
            }
        }
        rules.add(matched("cartesian-product", "Декартово произведение", "Join(TRUE) → TableScan × TableScan",
                "Нет условия, связывающего две таблицы: получаются все сочетания строк.",
                "Если все сочетания не нужны, добавьте условие соединения.", join, join.getLeft(), join.getRight()));
    }

    private static boolean containsNullComparison(RexNode node) {
        if (!(node instanceof RexCall call)) {
            return false;
        }
        if (List.of(SqlKind.EQUALS, SqlKind.NOT_EQUALS).contains(call.getKind())
                && call.operands.stream().anyMatch(operand -> RexUtil.isNullLiteral(operand, true))) {
            return true;
        }
        return List.of(SqlKind.AND, SqlKind.OR, SqlKind.NOT).contains(call.getKind())
                && call.operands.stream().anyMatch(Analyzer::containsNullComparison);
    }

    private static RuleResult matched(String ruleId, String title, String pattern,
                                      String explanation, String recommendation, RelNode... nodes) {
        return new RuleResult(ruleId, title, pattern, Status.FOUND, explanation, recommendation,
                java.util.Arrays.stream(nodes).map(RelNode::getId).toList());
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
