package io.github.vaneshik.sqlantipatterns;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.externalize.RelWriterImpl;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.util.Pair;

public record PlanNode(int id, String type, Map<String, String> attributes,
                       List<String> fields, List<PlanNode> inputs, List<Subquery> subqueries) {
    public record Subquery(String operator, PlanNode plan) {}

    static PlanNode from(RelNode rel) {
        Map<String, String> attributes = new LinkedHashMap<>();
        rel.explain(new RelWriterImpl(new PrintWriter(Writer.nullWriter())) {
            @Override
            protected void explain_(RelNode node, List<Pair<String, Object>> values) {
                for (Pair<String, Object> value : values) {
                    if (!(value.right instanceof RelNode)) {
                        attributes.put(value.left, String.valueOf(value.right));
                    }
                }
            }
        });
        List<Subquery> subqueries = new ArrayList<>();
        rel.accept(new RexShuttle() {
            @Override
            public RexNode visitSubQuery(RexSubQuery query) {
                subqueries.add(new Subquery(query.getKind().name(), from(query.rel)));
                return super.visitSubQuery(query);
            }
        });
        return new PlanNode(rel.getId(), rel.getRelTypeName(), attributes,
                rel.getRowType().getFieldList().stream()
                        .map(field -> field.getName() + ": " + field.getType().getFullTypeString()).toList(),
                rel.getInputs().stream().map(PlanNode::from).toList(), List.copyOf(subqueries));
    }
}
