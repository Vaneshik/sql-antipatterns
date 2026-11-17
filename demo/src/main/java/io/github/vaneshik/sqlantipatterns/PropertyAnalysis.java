package io.github.vaneshik.sqlantipatterns;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.NlsString;

public final class PropertyAnalysis {
    public enum Status { PROVED, UNKNOWN, UNSUPPORTED, TIMEOUT }

    public record Result(String kind, int nodeId, int scopeId, Status status, String reason,
                         int obligations, String representation, double elapsedMillis) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path WORKER = extractWorker();

    static List<Result> analyze(String sql, RelNode plan) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        export(plan, nodes, schema);
        Map<String, Object> request = Map.of("sql", sql, "schema", schema,
                "analysis", Map.of("root", plan.getId(), "nodes", nodes));
        Process process = null;
        Path output = null;
        try {
            output = Files.createTempFile("sql-properties-", ".json");
            process = new ProcessBuilder("python3", "-I", "-B", "-c",
                    "import runpy,sys; sys.path.insert(0,sys.argv[1]); "
                            + "runpy.run_path(sys.argv[1]+'/worker.py',run_name='__main__')",
                    WORKER.toString())
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (var input = process.getOutputStream()) {
                JSON.writeValue(input, request);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Property analysis process exceeded 10 seconds");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Property analysis failed. Install demo/requirements.txt for python3.");
            }
            return JSON.readValue(output.toFile(), new TypeReference<List<Result>>() {});
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot run property analysis with python3", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Property analysis interrupted", failure);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException failure) {
                    output.toFile().deleteOnExit();
                }
            }
        }
    }

    private static Path extractWorker() {
        try {
            Path directory = Files.createTempDirectory("sql-properties-");
            directory.toFile().deleteOnExit();
            for (String file : List.of("worker.py", "symbolic.py", "symbolic_mux.py", "bridge.py")) {
                Path destination = directory.resolve(file);
                try (var source = PropertyAnalysis.class.getResourceAsStream("/analysis/" + file)) {
                    if (source == null) {
                        throw new IOException("Missing analysis resource " + file);
                    }
                    Files.copy(source, destination);
                }
                destination.toFile().deleteOnExit();
            }
            return directory;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot extract property analysis", failure);
        }
    }

    private static Map<String, Object> expression(RexNode expression) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", expression.getType().getFullTypeString().replace(" NOT NULL", ""));
        if (expression instanceof RexInputRef ref) {
            result.put("ref", ref.getIndex());
        } else if (expression instanceof RexLiteral literal) {
            Object value = literal.isNull() ? null : literal.getValue2();
            result.put("lit", value instanceof NlsString string ? string.getValue() : value);
        } else if (expression instanceof RexCall call && !(expression instanceof RexSubQuery)
                && call.getOperator().isDeterministic()) {
            result.put("op", call.getKind().name());
            result.put("name", call.getOperator().getName());
            result.put("args", call.operands.stream().map(PropertyAnalysis::expression).toList());
        } else {
            result.put("unsupported", expression.toString());
        }
        return result;
    }

    private static void export(RelNode rel, Map<String, Object> nodes, Map<String, Object> schema) {
        String id = Integer.toString(rel.getId());
        if (nodes.containsKey(id)) {
            return;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", rel.getId());
        node.put("kind", rel.getRelTypeName());
        node.put("inputs", rel.getInputs().stream().map(RelNode::getId).toList());
        node.put("fields", rel.getRowType().getFieldList().stream().map(field -> Map.of(
                "name", field.getName(), "nullable", field.getType().isNullable(),
                "type", field.getType().getFullTypeString().replace(" NOT NULL", ""))).toList());
        if (rel instanceof TableScan scan) {
            List<String> name = scan.getTable().getQualifiedName();
            node.put("table", name);
            List<ImmutableBitSet> keys = scan.getTable().getKeys();
            schema.put(name.getLast(), Map.of("strict", keys == null ? List.of() : keys.stream()
                    .filter(key -> key.stream().allMatch(index -> !scan.getRowType().getFieldList()
                            .get(index).getType().isNullable())).map(ImmutableBitSet::asList).toList(),
                    "nullable_unique", List.of()));
        }
        if (rel instanceof Project project) {
            node.put("projects", project.getProjects().stream().map(PropertyAnalysis::expression).toList());
        }
        if (rel instanceof Filter filter) {
            node.put("condition", expression(filter.getCondition()));
        }
        if (rel instanceof Join join) {
            node.put("condition", expression(join.getCondition()));
            node.put("join_type", join.getJoinType().name());
        }
        if (rel instanceof Aggregate aggregate) {
            node.put("group", aggregate.getGroupSet().asList());
            node.put("simple", aggregate.getGroupType() == Aggregate.Group.SIMPLE);
            node.put("aggs", aggregate.getAggCallList().stream().map(call -> call.getName()).toList());
        }
        if (rel instanceof Values values) {
            node.put("tuples", values.getTuples().stream()
                    .map(tuple -> tuple.stream().map(PropertyAnalysis::expression).toList()).toList());
        }
        if (rel instanceof SetOp set) {
            node.put("all", set.all);
        }
        nodes.put(id, node);
        rel.getInputs().forEach(input -> export(input, nodes, schema));
    }
}
