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
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
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

    public record Result(String plan, PlanNode tree, List<RuleResult> rules, Optimization optimized) {}

    public record Optimization(String sql, String plan, PlanNode tree, List<RuleResult> rules, boolean changed) {}

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
            RelNode rel = planner.rel(validated).project();
            String plan = RelOptUtil.toString(rel);
            PlanNode tree = PlanNode.from(rel);
            List<RuleResult> rules = checkRules(original, rel);
            SqlNode rewritten = SqlParser.create(statement, parserConfig).parseQuery();
            RelNode optimized = rel;
            List<RuleResult> optimizedRules = rules;
            String optimizedSql = sql;
            boolean changed = false;
            while (SqlRewrite.apply(rewritten, validated, optimized, optimizedRules)) {
                String rewrittenStatement = SqlRewrite.format(rewritten);
                optimizedSql = rewrittenStatement + ";";
                planner.close();
                planner.reset();
                validated = planner.validate(planner.parse(rewrittenStatement));
                optimized = planner.rel(validated).project();
                optimizedRules = checkRules(rewritten, optimized);
                changed = true;
            }
            return new Result(plan, tree, rules, new Optimization(optimizedSql, RelOptUtil.toString(optimized),
                    PlanNode.from(optimized), optimizedRules, changed));
        }
    }

    private static List<RuleResult> checkRules(SqlNode original, RelNode rel) {
        List<RuleResult> rules = new ArrayList<>();
        rules.add(distinctJoin(original, rel));
        inspect(rel, rules);
        inspectCartesianProduct(rel, rules);
        return List.copyOf(rules);
    }

    private static RuleResult distinctJoin(SqlNode original, RelNode rel) {
        if (original instanceof SqlOrderBy orderBy) {
            original = orderBy.query;
        }
        if (!(original instanceof SqlSelect select)) {
            return new RuleResult(Status.UNSUPPORTED,
                    "Поддерживается один SELECT без CTE и операций над множествами.", "");
        }
        if (select.getGroup() != null || select.getHaving() != null
                || !select.getWindowList().isEmpty()) {
            return new RuleResult(Status.UNSUPPORTED,
                    "GROUP BY, HAVING и окна пока не поддерживаются.", "");
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
        Sort pagination = null;
        while (rel instanceof Project || rel instanceof Sort) {
            if (rel instanceof Sort sort && (sort.fetch != null || sort.offset != null)) {
                pagination = sort;
            }
            rel = rel.getInput(0);
        }
        if (!(rel instanceof Aggregate aggregate)
                || !aggregate.getAggCallList().isEmpty()
                || !(aggregate.getInput() instanceof Project project)) {
            return new RuleResult(Status.UNSUPPORTED,
                    "План не соответствует DISTINCT над простой проекцией.", "");
        }
        RelNode input = project.getInput();
        if (input instanceof Filter filter) {
            if (!deterministicScalar(filter.getCondition())) {
                return new RuleResult(Status.UNSUPPORTED,
                        "Условия с окнами, подзапросами и недетерминированными функциями не переносятся.", "");
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
        if (!deterministicScalar(join.getCondition())
                || !RelOptUtil.InputFinder.bits(join.getCondition()).intersects(ImmutableBitSet.range(leftWidth))
                || !RelOptUtil.InputFinder.bits(join.getCondition()).intersects(
                        ImmutableBitSet.range(leftWidth, join.getRowType().getFieldCount()))) {
            return new RuleResult(Status.UNSUPPORTED,
                    "Нужно детерминированное условие ON, связывающее обе стороны соединения.", "");
        }
        ImmutableBitSet.Builder rightJoinColumns = ImmutableBitSet.builder();
        for (RexNode condition : RelOptUtil.conjunctions(join.getCondition())) {
            if (condition instanceof RexCall call && call.getKind() == SqlKind.EQUALS
                    && call.operands.get(0) instanceof RexInputRef first
                    && call.operands.get(1) instanceof RexInputRef second
                    && (first.getIndex() < leftWidth) != (second.getIndex() < leftWidth)) {
                rightJoinColumns.set(Math.max(first.getIndex(), second.getIndex()) - leftWidth);
            }
        }
        ImmutableBitSet.Builder projectedColumns = ImmutableBitSet.builder();
        for (RexNode column : project.getProjects()) {
            if (!deterministicScalar(column)
                    || !ImmutableBitSet.range(leftWidth).contains(RelOptUtil.InputFinder.bits(column))) {
                return new RuleResult(Status.UNSUPPORTED,
                        "В SELECT допускаются детерминированные выражения только от левой таблицы.", "");
            }
            if (column instanceof RexInputRef ref) {
                projectedColumns.set(ref.getIndex());
            }
        }
        ImmutableBitSet columns = projectedColumns.build();
        if (pagination != null) {
            if (pagination.getInput() != aggregate) {
                return new RuleResult(Status.UNSUPPORTED,
                        "Для LIMIT/OFFSET нужен ORDER BY непосредственно над результатом DISTINCT.", "");
            }
            ImmutableBitSet orderColumns = ImmutableBitSet.of(pagination.getCollation().getKeys());
            ImmutableBitSet.Builder orderedLeftColumns = ImmutableBitSet.builder();
            for (int index : orderColumns) {
                if (project.getProjects().get(index) instanceof RexInputRef ref) {
                    orderedLeftColumns.set(ref.getIndex());
                }
            }
            if (orderColumns.isEmpty()
                    || !(orderColumns.contains(ImmutableBitSet.range(aggregate.getGroupCount()))
                        || left.getTable().isKey(orderedLeftColumns.build()))) {
                return new RuleResult(Status.UNSUPPORTED,
                        "Для LIMIT/OFFSET нужен ORDER BY, однозначно определяющий порядок результата.", "");
            }
        }
        if (right.getTable().isKey(rightJoinColumns.build())) {
            return new RuleResult(Status.NOT_FOUND,
                    "Соединение идёт по ключу правой таблицы и не размножает строки левой.", "");
        }
        List<Integer> matchedNodeIds = project.getInput() instanceof Filter filter
                ? List.of(aggregate.getId(), project.getId(), filter.getId(), join.getId())
                : List.of(aggregate.getId(), project.getId(), join.getId());
        if (!left.getTable().isKey(columns)) {
            return new RuleResult("distinct-join-keep-distinct", "JOIN с сохранением DISTINCT",
                    "Aggregate → Project → [Filter] → Join", Status.FOUND,
                    "JOIN используется для проверки существования строк. В проекции нет ключа: "
                            + "разные строки левой таблицы могут дать одинаковый результат.",
                    "Замените JOIN на EXISTS. Сохраните DISTINCT, чтобы не изменить число дублей.",
                    matchedNodeIds);
        }
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
                    && deterministicScalar(filter.getCondition())) {
                boolean movable = RelOptUtil.conjunctions(filter.getCondition()).stream().anyMatch(predicate -> {
                    ImmutableBitSet columns = RelOptUtil.InputFinder.bits(predicate);
                    return !columns.isEmpty() && ImmutableBitSet.range(aggregate.getGroupCount()).contains(columns);
                });
                if (movable) {
                    rules.add(matched("group-filter", "Фильтр после группировки", "Filter → Aggregate",
                            "Часть HAVING зависит только от ключей группировки и может отсеивать строки до агрегации.",
                            "Перенесите условия по ключам в WHERE, сохранив условия по агрегатам в HAVING.", filter, aggregate));
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

    static boolean isBaseTable(SqlNode node) {
        if (node instanceof SqlCall call && call.getKind() == SqlKind.AS) {
            node = call.operand(0);
        }
        return node instanceof SqlIdentifier;
    }

    private static boolean deterministicScalar(RexNode expression) {
        return RexUtil.isDeterministic(expression)
                && RexUtil.SubQueryFinder.find(expression) == null
                && !RexOver.containsOver(expression);
    }
}
