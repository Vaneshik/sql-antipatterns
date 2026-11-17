"""Prototype symbolic bag-row collision calculus over exported Calcite RelNode.

UNSAT is a sufficient proof under the total-expression assumptions.
SAT remains UNKNOWN because outer absence is over-approximated.
"""

from dataclasses import dataclass
from functools import lru_cache
from itertools import product, combinations
import time
import z3


class Unsupported(Exception):
    pass


class Budget(Exception):
    pass


@dataclass
class Cell:
    value: object
    null: object


@dataclass
class Row:
    cells: list
    valid: object
    provenance: object
    bases: list
    outers: list


class Cells:
    """Index-preserving lazy row layout; unused attributes are never SMT-materialized."""

    def __init__(self, width, factory):
        self.width = width
        self.factory = factory
        self.cache = {}

    def __len__(self):
        return self.width

    def __getitem__(self, index):
        if isinstance(index, slice):
            return [self[i] for i in range(*index.indices(self.width))]
        if index < 0:
            index += self.width
        if not 0 <= index < self.width:
            raise IndexError(index)
        if index not in self.cache:
            self.cache[index] = self.factory(index)
        return self.cache[index]

    def __iter__(self):
        for i in range(self.width):
            yield self[i]

    def __add__(self, other):
        width = len(self)
        return Cells(
            width + len(other), lambda i: self[i] if i < width else other[i - width]
        )

    def __radd__(self, other):
        width = len(other)
        return Cells(
            width + len(self), lambda i: other[i] if i < width else self[i - width]
        )


def eq(a, b):
    return z3.And(a.null == b.null, z3.Or(a.null, a.value == b.value))


def land(xs):
    return z3.And(*xs) if xs else z3.BoolVal(True)


def lor(xs):
    return z3.Or(*xs) if xs else z3.BoolVal(False)


@lru_cache(maxsize=256)
def null_cell(typ="INTEGER"):
    cell = fresh_cell("null_constant_" + typ, typ, False)
    return Cell(cell.value, z3.BoolVal(True))


def references(expression):
    return (
        {expression["ref"]}
        if "ref" in expression
        else set().union(*(references(x) for x in expression.get("args", [])))
    )


def fresh_cell(name, typ, nullable):
    base_type = typ.split("(")[0]
    if base_type in ["TINYINT", "SMALLINT", "INTEGER", "BIGINT"]:
        value = z3.Int(name)
    elif base_type == "BOOLEAN":
        value = z3.Bool(name)
    elif base_type in ["CHAR", "VARCHAR"]:
        value = z3.String(name)
    else:
        value = z3.Const(name, z3.DeclareSort("opaque_" + typ.replace(" ", "_")))
    return Cell(value, z3.Bool(name + "_null") if nullable else z3.BoolVal(False))


def validate_fragment(nodes, root):

    def scalar(expression):
        if "ref" in expression or "lit" in expression:
            return
        allowed = [
            "AND",
            "OR",
            "NOT",
            "IS_NULL",
            "IS_NOT_NULL",
            "IS_TRUE",
            "IS_FALSE",
            "IS_NOT_TRUE",
            "IS_NOT_FALSE",
            "EQUALS",
            "NOT_EQUALS",
            "LESS_THAN",
            "LESS_THAN_OR_EQUAL",
            "GREATER_THAN",
            "GREATER_THAN_OR_EQUAL",
            "IS_NOT_DISTINCT_FROM",
            "IS_DISTINCT_FROM",
        ]
        if expression.get("op") not in allowed and expression.get("name") not in [
            "ABS",
            "UPPER",
        ]:
            identity = expression.get("op") == "CAST" and expression.get(
                "type", ""
            ).replace(" NOT NULL", "") == expression["args"][0].get("type", "").replace(
                " NOT NULL", ""
            )
            if not identity:
                raise Unsupported(
                    "outside pure scalar fragment " + str(expression.get("name"))
                )
        for arg in expression.get("args", []):
            scalar(arg)

    seen = set()

    def visit(node_id):
        if node_id in seen:
            return
        seen.add(node_id)
        node = nodes[str(node_id)]
        kind = node["kind"]
        if not kind.endswith(
            ("TableScan", "Project", "Filter", "Aggregate", "Join", "Union", "Values")
        ):
            raise Unsupported("fragment " + kind)
        if kind.endswith("Aggregate") and (
            node.get("aggs") or not node.get("simple", True) or (not node.get("group"))
        ):
            raise Unsupported("aggregate fragment")
        for expression in node.get("projects", []):
            scalar(expression)
        if "condition" in node:
            scalar(node["condition"])
        for child in node["inputs"]:
            visit(child)

    visit(root)


def eval_expr(expression, cells):
    if "ref" in expression:
        return cells[expression["ref"]]
    if "lit" in expression:
        v = expression["lit"]
        typ = expression.get("type", "INTEGER")
        if v is None:
            return null_cell(typ)
        if isinstance(v, bool):
            v = z3.BoolVal(v)
        elif isinstance(v, int):
            v = z3.IntVal(v)
        elif isinstance(v, str):
            if any(
                (
                    t in typ
                    for t in ["DATE", "TIME", "INTERVAL", "DECIMAL", "FLOAT", "DOUBLE"]
                )
            ):
                raise Unsupported("literal " + typ)
            v = z3.StringVal(v)
        else:
            raise Unsupported("literal " + str(expression))
        return Cell(v, z3.BoolVal(False))
    op = expression.get("op")
    args = [eval_expr(x, cells) for x in expression.get("args", [])]
    if op in ["AND", "OR"]:
        tr = [z3.And(z3.Not(c.null), c.value) for c in args]
        fa = [z3.And(z3.Not(c.null), z3.Not(c.value)) for c in args]
        true = land(tr) if op == "AND" else lor(tr)
        false = lor(fa) if op == "AND" else land(fa)
        return Cell(true, z3.Not(z3.Or(true, false)))
    if op == "NOT":
        return Cell(z3.Not(args[0].value), args[0].null)
    if op in ["IS_NULL", "IS_NOT_NULL"]:
        return Cell(
            args[0].null if op == "IS_NULL" else z3.Not(args[0].null), z3.BoolVal(False)
        )
    if op in ["IS_TRUE", "IS_FALSE", "IS_NOT_TRUE", "IS_NOT_FALSE"]:
        a = args[0]
        t = z3.And(z3.Not(a.null), a.value)
        f = z3.And(z3.Not(a.null), z3.Not(a.value))
        v = {
            "IS_TRUE": t,
            "IS_FALSE": f,
            "IS_NOT_TRUE": z3.Not(t),
            "IS_NOT_FALSE": z3.Not(f),
        }[op]
        return Cell(v, z3.BoolVal(False))
    if op in ["IS_NOT_DISTINCT_FROM", "IS_DISTINCT_FROM"]:
        v = eq(*args)
        return Cell(v if op == "IS_NOT_DISTINCT_FROM" else z3.Not(v), z3.BoolVal(False))
    if op in [
        "EQUALS",
        "NOT_EQUALS",
        "LESS_THAN",
        "LESS_THAN_OR_EQUAL",
        "GREATER_THAN",
        "GREATER_THAN_OR_EQUAL",
    ]:
        a, b = args
        if a.value.sort() != b.value.sort():
            raise Unsupported("comparison sort mismatch")
        if op not in ["EQUALS", "NOT_EQUALS"] and a.value.sort().kind() not in [
            z3.Z3_INT_SORT,
            z3.Z3_REAL_SORT,
        ]:
            raise Unsupported("ordered comparison outside numeric domain")
        v = {
            "EQUALS": lambda: a.value == b.value,
            "NOT_EQUALS": lambda: a.value != b.value,
            "LESS_THAN": lambda: a.value < b.value,
            "LESS_THAN_OR_EQUAL": lambda: a.value <= b.value,
            "GREATER_THAN": lambda: a.value > b.value,
            "GREATER_THAN_OR_EQUAL": lambda: a.value >= b.value,
        }[op]()
        return Cell(v, z3.Or(a.null, b.null))
    if expression.get("name") == "ABS":
        a = args[0]
        return Cell(z3.If(a.value >= 0, a.value, -a.value), a.null)
    if expression.get("name") == "UPPER":
        a = args[0]
        return Cell(
            z3.Function("pure_upper", z3.StringSort(), z3.StringSort())(a.value), a.null
        )
    if op == "CAST":
        typ = lambda x: x.get("type", "").replace(" NOT NULL", "").strip()
        if typ(expression) == typ(expression["args"][0]):
            return args[0]
    raise Unsupported("expression " + str(expression.get("op")))


def truth(expression, cells):
    c = eval_expr(expression, cells)
    return z3.And(z3.Not(c.null), c.value)


def same_provenance(a, b):
    if a[0] != b[0] or a[1] != b[1]:
        return z3.BoolVal(False)
    if a[0] == "scan":
        return a[2] == b[2]
    if a[0] == "absent":
        return z3.BoolVal(True)
    if a[0] == "group":
        return land([eq(x, y) for x, y in zip(a[2], b[2])])
    if a[0] == "branch":
        return same_provenance(a[2], b[2])
    if a[0] == "join":
        return z3.And(same_provenance(a[2], b[2]), same_provenance(a[3], b[3]))
    if a[0] == "optional":
        return z3.And(
            same_provenance(a[2], b[2]),
            a[4] == b[4],
            z3.Implies(a[4], same_provenance(a[3], b[3])),
        )
    if a[0] == "choice":
        return z3.And(
            a[2] == b[2],
            *[
                z3.Implies(a[2] == i, same_provenance(p, q))
                for i, (p, q) in enumerate(zip(a[3], b[3]))
            ],
        )
    raise Unsupported("provenance")


class Symbolic:
    def __init__(self, analysis, profile, deadline):
        self.nodes = analysis["nodes"]
        self.root = analysis["root"]
        self.profile = profile
        self.deadline = deadline
        self.max_variants = 256
        validate_fragment(self.nodes, self.root)

    def build(self, node_id, prefix="q", path=(), needed=None):
        if time.perf_counter_ns() > self.deadline:
            raise Budget()
        node = self.nodes[str(node_id)]
        kind = node["kind"]
        role = tuple(path)
        needed = set(range(len(node["fields"]))) if needed is None else set(needed)
        if kind.endswith("TableScan"):
            table = node["table"][-1]
            spec = self.profile[table]

            def materialize(i):
                field = node["fields"][i]
                name = f"{prefix}_{node_id}_{path}_{i}"
                return fresh_cell(name, field["type"], field["nullable"])

            cells = Cells(len(node["fields"]), materialize)
            rid = z3.Int(f"{prefix}_{node_id}_{path}_rid")
            base = dict(
                table=table, rid=rid, cells=cells, spec=spec, role=role, active=needed
            )
            return [Row(cells, z3.BoolVal(True), ("scan", role, rid), [base], [])]
        demands = [
            set(range(len(self.nodes[str(i)]["fields"]))) for i in node["inputs"]
        ]
        if kind.endswith("Project"):
            demands = [set().union(*(references(node["projects"][i]) for i in needed))]
        elif kind.endswith("Filter"):
            demands = [needed | references(node["condition"])]
        elif kind.endswith("Aggregate"):
            demands = [set(node.get("group", []))]
        elif kind.endswith("Union"):
            demands = [
                needed if node.get("all", False) else set(range(len(node["fields"])))
                for _ in node["inputs"]
            ]
        elif kind.endswith("Join"):
            width = len(self.nodes[str(node["inputs"][0])]["fields"])
            allrefs = needed | references(node["condition"])
            demands = [
                {i for i in allrefs if i < width},
                {i - width for i in allrefs if i >= width},
            ]
        children = [
            self.build(i, prefix, path + (j,), needed=demands[j])
            for j, i in enumerate(node["inputs"])
        ]
        if kind.endswith("Project"):
            return [
                Row(
                    Cells(
                        len(node["projects"]),
                        lambda i, r=r: eval_expr(node["projects"][i], r.cells),
                    ),
                    r.valid,
                    r.provenance,
                    r.bases,
                    r.outers,
                )
                for r in children[0]
            ]
        if kind.endswith("Filter"):
            return [
                Row(
                    r.cells,
                    z3.And(r.valid, truth(node["condition"], r.cells)),
                    r.provenance,
                    r.bases,
                    r.outers,
                )
                for r in children[0]
            ]
        if kind.endswith("Aggregate"):
            if (
                not node.get("simple", True)
                or node.get("aggs")
                or (not node.get("group"))
            ):
                raise Unsupported("aggregate functions/grouping")
            out = []
            for r in children[0]:
                cells = [r.cells[i] for i in node["group"]]
                out.append(
                    Row(cells, r.valid, ("group", role, cells), r.bases, r.outers)
                )
            return out
        if kind.endswith("Union"):
            out = []
            for j, rows in enumerate(children):
                for r in rows:
                    prov = (
                        ("branch", (role, j), r.provenance)
                        if node.get("all", False)
                        else ("group", role, r.cells)
                    )
                    out.append(Row(r.cells, r.valid, prov, r.bases, r.outers))
            if len(out) > self.max_variants:
                raise Unsupported("variant cap")
            return out
        if kind.endswith("Join"):
            jt = node["join_type"]
            if jt not in ["INNER", "LEFT", "RIGHT"]:
                raise Unsupported("join " + jt)
            out = []
            for l, r in product(*children):
                cells = l.cells + r.cells
                valid = z3.And(l.valid, r.valid, truth(node["condition"], cells))
                tags = (
                    l.outers
                    + r.outers
                    + (
                        [(role, l.provenance, True)]
                        if jt == "LEFT"
                        else [(role, r.provenance, True)]
                        if jt == "RIGHT"
                        else []
                    )
                )
                out.append(
                    Row(
                        cells,
                        valid,
                        ("join", role, l.provenance, r.provenance),
                        l.bases + r.bases,
                        tags,
                    )
                )
            if jt == "LEFT":
                for l in children[0]:
                    nulls = [
                        null_cell(f["type"])
                        for f in self.nodes[str(node["inputs"][1])]["fields"]
                    ]
                    out.append(
                        Row(
                            l.cells + nulls,
                            l.valid,
                            ("join", role, l.provenance, ("absent", role)),
                            l.bases,
                            l.outers + [(role, l.provenance, False)],
                        )
                    )
            if jt == "RIGHT":
                for r in children[1]:
                    nulls = [
                        null_cell(f["type"])
                        for f in self.nodes[str(node["inputs"][0])]["fields"]
                    ]
                    out.append(
                        Row(
                            nulls + r.cells,
                            r.valid,
                            ("join", role, ("absent", role), r.provenance),
                            r.bases,
                            r.outers + [(role, r.provenance, False)],
                        )
                    )
            if len(out) > self.max_variants:
                raise Unsupported("variant cap")
            return out
        if kind.endswith("Values"):
            return [
                Row(
                    [eval_expr(expression, []) for expression in t],
                    z3.BoolVal(True),
                    ("branch", (role, i), ("absent", role)),
                    [],
                    [],
                )
                for i, t in enumerate(node["tuples"])
            ]
        raise Unsupported(kind)

    def consistency(self, rows):
        bases = []
        for row in rows:
            for b in row.bases:
                if not any((z3.eq(b["rid"], old["rid"]) for old in bases)):
                    bases.append(b)
        constraints = []
        for a, b in combinations(bases, 2):
            if time.perf_counter_ns() > self.deadline:
                raise Budget()
            if a["table"] != b["table"]:
                continue
            active = a["active"] & b["active"]
            same_values = land(
                [eq(a["cells"][i], b["cells"][i]) for i in sorted(active)]
            )
            constraints.append(z3.Implies(a["rid"] == b["rid"], same_values))
            for category in ["strict", "nullable_unique"]:
                for key in a["spec"][category]:
                    if not set(key) <= active:
                        continue
                    equal = land(
                        [
                            z3.And(
                                z3.Not(a["cells"][i].null),
                                z3.Not(b["cells"][i].null),
                                a["cells"][i].value == b["cells"][i].value,
                            )
                            for i in key
                        ]
                    )
                    constraints.append(z3.Implies(equal, a["rid"] == b["rid"]))
        for a, b in combinations([tag for r in rows for tag in r.outers], 2):
            if a[0] == b[0] and a[2] != b[2]:
                constraints.append(z3.Not(same_provenance(a[1], b[1])))
        return constraints


def alternative_bound(nodes, root, cap=257):
    """Representation paths, not data cardinality. Saturating structural bound."""
    memo = {}

    def count(node_id):
        if node_id in memo:
            return memo[node_id]
        node = nodes[str(node_id)]
        children = [count(i) for i in node["inputs"]]
        kind = node["kind"]
        if kind.endswith("Union"):
            value = sum(children)
        elif kind.endswith("Join"):
            value = children[0] * children[1]
            if node["join_type"] == "LEFT":
                value += children[0]
            elif node["join_type"] == "RIGHT":
                value += children[1]
        elif kind.endswith("Values"):
            value = len(node.get("tuples", []))
        else:
            value = children[0] if children else 1
        memo[node_id] = min(cap, value)
        return memo[node_id]

    return count(root)


def obligations(target, analysis, profile, deadline):
    if alternative_bound(analysis["nodes"], analysis["root"]) > 4:
        from symbolic_mux import SymbolicMux

        engine = SymbolicMux(analysis, profile, deadline)
    else:
        engine = Symbolic(analysis, profile, deadline)
    node_id = target["node"]
    path = tuple(target["relative_path"])
    node = engine.nodes[str(node_id)]
    kind = target["kind"]
    formulas = []
    if kind == "empty":
        for r in engine.build(node_id, "e", path):
            formulas.append((z3.And(r.valid, *engine.consistency([r])), r.bases))
    elif kind in ["distinct", "union"]:
        target = node["inputs"][0] if kind == "distinct" else node_id
        original_all = node.get("all")
        node["all"] = True if kind == "union" else original_all
        try:
            one = engine.build(target, "u", path + (0,) if kind == "distinct" else path)
            two = engine.build(target, "v", path + (0,) if kind == "distinct" else path)
        finally:
            if original_all is None:
                node.pop("all", None)
            else:
                node["all"] = original_all
        for a, b in product(one, two):
            observed_a = (
                [a.cells[i] for i in node["group"]] if kind == "distinct" else a.cells
            )
            observed_b = (
                [b.cells[i] for i in node["group"]] if kind == "distinct" else b.cells
            )
            f = z3.And(
                a.valid,
                b.valid,
                land([eq(x, y) for x, y in zip(observed_a, observed_b)]),
                z3.Not(same_provenance(a.provenance, b.provenance)),
                *engine.consistency([a, b]),
            )
            formulas.append((f, a.bases + b.bases))
    elif kind == "outer_inner":
        capsule = engine.root
        capsule_path = ()
        for r in engine.build(capsule, "o", capsule_path):
            for tag in r.outers:
                if tag[0] != path:
                    continue
                if len(tag) > 3:
                    formulas.append(
                        (
                            z3.And(
                                r.valid,
                                tag[3],
                                z3.Not(tag[2]),
                                *engine.consistency([r]),
                            ),
                            r.bases,
                        )
                    )
                elif not tag[2]:
                    formulas.append(
                        (z3.And(r.valid, *engine.consistency([r])), r.bases)
                    )
    elif kind == "join_remove":
        expressions = [
            {"ref": i} for i in range(len(engine.nodes[str(engine.root)]["fields"]))
        ]
        predicates = []
        barriers = []
        current = engine.root
        insensitive = False

        def subst(expression, projects):
            if "ref" in expression:
                return projects[expression["ref"]]
            return (
                dict(expression, args=[subst(a, projects) for a in expression["args"]])
                if "args" in expression
                else expression
            )

        while current != node_id:
            p = engine.nodes[str(current)]
            if p["kind"].endswith("Project"):
                expressions = [
                    subst(expression, p["projects"]) for expression in expressions
                ]
                predicates = [
                    subst(expression, p["projects"]) for expression in predicates
                ]
                barriers = [subst(expression, p["projects"]) for expression in barriers]
            elif p["kind"].endswith("Filter"):
                predicates.append(p["condition"])
            elif (
                p["kind"].endswith("Aggregate")
                and p.get("simple", True)
                and (not p.get("aggs"))
                and p.get("group")
            ):
                projects = [{"ref": i} for i in p["group"]]
                expressions = [
                    subst(expression, projects) for expression in expressions
                ]
                predicates = [subst(expression, projects) for expression in predicates]
                barriers = [
                    subst(expression, projects) for expression in barriers
                ] + projects
                insensitive = True
            else:
                raise Unsupported("join-removal consumer")
            current = p["inputs"][0]
        leftwidth = len(engine.nodes[str(node["inputs"][0])]["fields"])
        side = 0 if node["join_type"] == "LEFT" else 1
        kept = (
            set(range(leftwidth))
            if side == 0
            else set(range(leftwidth, len(node["fields"])))
        )
        if not all(
            (
                references(expression) <= kept
                for expression in expressions + predicates + barriers
            )
        ):
            raise Unsupported("removed-side values/group keys observed")
        if insensitive:
            engine.build(engine.root, "purity_check")
            return []
        ls = engine.build(node["inputs"][side], "l", path + (side,))
        rs0 = engine.build(node["inputs"][1 - side], "r0", path + (1 - side,))
        rs1 = engine.build(node["inputs"][1 - side], "r1", path + (1 - side,))
        for l, r, s in product(ls, rs0, rs1):
            c0 = l.cells + r.cells if side == 0 else r.cells + l.cells
            c1 = l.cells + s.cells if side == 0 else s.cells + l.cells
            f = z3.And(
                l.valid,
                r.valid,
                s.valid,
                truth(node["condition"], c0),
                truth(node["condition"], c1),
                *[truth(p, c0) for p in predicates],
                z3.Not(same_provenance(r.provenance, s.provenance)),
                *engine.consistency([l, r, s]),
            )
            formulas.append((f, l.bases + r.bases + s.bases))
    return formulas
