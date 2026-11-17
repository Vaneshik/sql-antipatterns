"""Property-directed collision analysis for the demo's typed RelNode export."""

import json
import sys
import time

import z3

from bridge import dialect_bridge
from symbolic import Budget, Unsupported, alternative_bound, obligations


def discover(analysis):
    nodes = analysis["nodes"]
    targets = []

    def visit(node_id, ancestors):
        node = nodes[str(node_id)]
        kind = node["kind"]
        kinds = []
        if kind.endswith("Filter"):
            kinds = ["empty"]
        elif (
            kind.endswith("Aggregate")
            and node["simple"]
            and not node["aggs"]
            and node["group"]
        ):
            kinds = ["distinct"]
        elif kind.endswith("Join") and node["join_type"] in ["LEFT", "RIGHT"]:
            kinds = ["join_remove", "outer_inner"]
        elif kind.endswith("Union") and not node["all"]:
            kinds = ["union"]
        for property_kind in kinds:
            scope = node_id
            path = []
            if property_kind in ["join_remove", "outer_inner"]:
                for parent_id, edge in reversed(ancestors):
                    parent = nodes[str(parent_id)]
                    transparent = parent["kind"].endswith(("Project", "Filter"))
                    if parent["kind"].endswith("Aggregate"):
                        transparent = (
                            parent["simple"]
                            and not parent["aggs"]
                            and bool(parent["group"])
                        )
                    if not transparent:
                        break
                    scope = parent_id
                    path.insert(0, edge)
            targets.append(
                dict(kind=property_kind, node=node_id, scope=scope, relative_path=path)
            )
        for edge, child in enumerate(node["inputs"]):
            visit(child, ancestors + [(node_id, edge)])

    visit(analysis["root"], [])
    return targets


def analyze(request):
    start = time.perf_counter_ns()
    deadline = start + 1_000_000_000
    analysis = request["analysis"]
    compatible, bridge_reason = dialect_bridge(request["sql"])
    results = []
    for target in discover(analysis):
        begin = time.perf_counter_ns()
        result = dict(
            kind=target["kind"],
            nodeId=target["node"],
            scopeId=target["scope"],
            status="UNKNOWN",
            reason="",
            obligations=0,
            representation="",
        )
        try:
            if begin >= deadline:
                raise Budget()
            if not compatible:
                raise Unsupported(bridge_reason)
            scoped = dict(analysis, root=target["scope"])
            bound = alternative_bound(scoped["nodes"], scoped["root"])
            result["representation"] = "guarded DAG" if bound > 4 else "enumeration"
            formulas = obligations(target, scoped, request["schema"], deadline)
            result["obligations"] = len(formulas)
            solver = z3.Solver()
            result["status"] = "PROVED"
            for formula, _ in formulas:
                remaining = int((deadline - time.perf_counter_ns()) / 1_000_000)
                if remaining <= 0:
                    raise Budget()
                solver.set(timeout=remaining)
                solver.push()
                solver.add(formula)
                answer = solver.check()
                if answer == z3.unknown:
                    reason = solver.reason_unknown()
                    status = "TIMEOUT" if reason in ["timeout", "canceled"] else "UNKNOWN"
                    result.update(status=status, reason=reason)
                elif answer == z3.sat:
                    result.update(
                        status="UNKNOWN", reason="Collision not excluded by the model"
                    )
                solver.pop()
                if answer != z3.unsat:
                    break
            if time.perf_counter_ns() > deadline:
                raise Budget()
        except Unsupported as failure:
            result.update(status="UNSUPPORTED", reason=str(failure))
        except Budget:
            result.update(
                status="TIMEOUT", reason="Query analysis budget exhausted (1000 ms)"
            )
        result["elapsedMillis"] = (time.perf_counter_ns() - begin) / 1_000_000
        results.append(result)
    return results


if __name__ == "__main__":
    json.dump(analyze(json.load(sys.stdin)), sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
