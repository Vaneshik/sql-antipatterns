package io.github.vaneshik.sqlantipatterns;

import java.util.List;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;

final class SqlRewrite {
    static boolean apply(SqlNode query, RelNode plan, List<Analyzer.RuleResult> rules) {
        SqlNode body = query instanceof SqlOrderBy orderBy ? orderBy.query : query;
        if (!(body instanceof SqlSelect select)) {
            return false;
        }
        SqlNode from = select.getFrom();
        if (!Analyzer.isBaseTable(from) && !(from instanceof SqlJoin join
                && Analyzer.isBaseTable(join.getLeft()) && Analyzer.isBaseTable(join.getRight()))) {
            return false;
        }
        if (rules.getFirst().status() == Analyzer.Status.FOUND) {
            SqlJoin join = (SqlJoin) from;
            var pos = select.getParserPosition();
            SqlSelect exists = new SqlSelect(pos, SqlNodeList.EMPTY,
                    new SqlNodeList(List.of(SqlLiteral.createExactNumeric("1", pos)), pos),
                    join.getRight(), and(join.getCondition(), select.getWhere()),
                    null, null, SqlNodeList.EMPTY, null, null, null, null, null);
            if (rules.getFirst().ruleId().equals("distinct-join")) {
                removeDistinct(select);
            }
            select.setFrom(join.getLeft());
            select.setWhere(SqlStdOperatorTable.EXISTS.createCall(pos, exists));
            return true;
        }
        boolean changed = false;
        for (Analyzer.RuleResult rule : rules) {
            if (rule.status() != Analyzer.Status.FOUND) {
                continue;
            }
            if (rule.ruleId().equals("group-filter") && select.getHaving() != null) {
                select.setWhere(and(select.getWhere(), select.getHaving()));
                select.setHaving(null);
                changed = true;
            }
            if (rule.ruleId().equals("left-join-filter") && from instanceof SqlJoin join
                    && join.getJoinType() == JoinType.LEFT) {
                select.setFrom(new SqlJoin(join.getParserPosition(), join.getLeft(), join.isNaturalNode(),
                        JoinType.INNER.symbol(join.getParserPosition()), join.getRight(),
                        join.getConditionTypeNode(), join.getCondition()));
                changed = true;
            }
            if (rule.ruleId().equals("redundant-distinct")) {
                RelNode aggregate = plan;
                while (aggregate instanceof Project || aggregate instanceof Sort) {
                    aggregate = aggregate.getInput(0);
                }
                if (!(aggregate instanceof Aggregate) || !rule.matchedNodeIds().contains(aggregate.getId())) {
                    continue;
                }
                if (select.isDistinct()) {
                    removeDistinct(select);
                    changed = true;
                } else if (select.getGroup() != null && select.getHaving() == null) {
                    select.setGroupBy(null);
                    changed = true;
                }
            }
        }
        return changed;
    }

    static String format(SqlNode query) {
        return new SqlPrettyWriter(SqlPrettyWriter.config()
                .withDialect(PostgresqlSqlDialect.DEFAULT)
                .withQuoteAllIdentifiers(false)
                .withIndentation(2)
                .withSelectListItemsOnSeparateLines(false)
                .withWhereListItemsOnSeparateLines(true)) {
            private int subqueryDepth;

            @Override
            protected Frame startList(FrameType type, String keyword, String open, String close) {
                Frame frame = super.startList(type, keyword, open, close);
                if (type == FrameTypeEnum.SUB_QUERY) {
                    newlineAndIndent(++subqueryDepth * 4);
                }
                return frame;
            }

            @Override
            public void endList(Frame frame) {
                if (frame.getFrameType() == FrameTypeEnum.SUB_QUERY) {
                    newlineAndIndent(--subqueryDepth * 4);
                }
                super.endList(frame);
            }
        }.format(query);
    }

    private static void removeDistinct(SqlSelect select) {
        SqlNodeList keywords = (SqlNodeList) select.getOperandList().getFirst();
        select.setOperand(0, new SqlNodeList(keywords.getList().stream()
                .filter(node -> ((SqlLiteral) node).symbolValue(SqlSelectKeyword.class) != SqlSelectKeyword.DISTINCT)
                .toList(), keywords.getParserPosition()));
    }

    private static SqlNode and(SqlNode left, SqlNode right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return SqlStdOperatorTable.AND.createCall(left.getParserPosition(), left, right);
    }

}
