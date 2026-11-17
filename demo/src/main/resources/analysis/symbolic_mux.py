"""Path-merged tuple presence for nested outer-join and UNION alternatives."""

from itertools import combinations
import time
import z3
from symbolic import (
    Symbolic,
    Row,
    Cell,
    Cells,
    Unsupported,
    Budget,
    truth,
    eq,
    land,
    lor,
    same_provenance,
    null_cell,
    references,
)


def boolean(x):
    return z3.BoolVal(x) if isinstance(x, bool) else x


def gate_bases(bases, guard):
    return [
        dict(b, present=z3.And(boolean(b.get("present", True)), guard)) for b in bases
    ]


def gate_tags(tags, guard):
    return [
        (
            t[0],
            t[1],
            boolean(t[2]),
            z3.And(boolean(t[3]) if len(t) > 3 else z3.BoolVal(True), guard),
        )
        for t in tags
    ]


class SymbolicMux(Symbolic):
    def merge(self, rows, node, prefix, path, grouped=False):
        role = tuple(path)
        selector = z3.Int(f"{prefix}_{node['id']}_{path}_branch")

        def choose(i):
            values = [r.cells[i] for r in rows]
            value = values[-1].value
            isnull = values[-1].null
            for j in reversed(range(len(values) - 1)):
                if values[j].value.sort() != value.sort():
                    raise Unsupported("union mux sorts")
                value = z3.If(selector == j, values[j].value, value)
                isnull = z3.If(selector == j, values[j].null, isnull)
            return Cell(value, isnull)

        cells = Cells(len(node["fields"]), choose)
        valid = z3.And(
            selector >= 0,
            selector < len(rows),
            lor([z3.And(selector == j, r.valid) for j, r in enumerate(rows)]),
        )
        provenance = (
            ("group", role, list(cells))
            if grouped
            else ("choice", role, selector, [r.provenance for r in rows])
        )
        bases = [
            b for j, r in enumerate(rows) for b in gate_bases(r.bases, selector == j)
        ]
        tags = [
            t for j, r in enumerate(rows) for t in gate_tags(r.outers, selector == j)
        ]
        return [Row(cells, valid, provenance, bases, tags)]

    def build(self, node_id, prefix="q", path=(), needed=None):
        if time.perf_counter_ns() > self.deadline:
            raise Budget()
        node = self.nodes[str(node_id)]
        kind = node["kind"]
        role = tuple(path)
        if kind.endswith("Values") and (not node.get("tuples")):
            return [
                Row(
                    Cells(
                        len(node["fields"]),
                        lambda i: null_cell(node["fields"][i]["type"]),
                    ),
                    z3.BoolVal(False),
                    ("absent", role),
                    [],
                    [],
                )
            ]
        if kind.endswith("Values"):
            rows = super().build(node_id, prefix, path, needed)
            return rows if len(rows) == 1 else self.merge(rows, node, prefix, path)
        if not (
            kind.endswith("Union")
            or (kind.endswith("Join") and node["join_type"] in ["LEFT", "RIGHT"])
        ):
            return super().build(node_id, prefix, path, needed)
        needed = set(range(len(node["fields"]))) if needed is None else set(needed)
        if kind.endswith("Union"):
            require_all = not node.get("all", False)
            children = [
                self.build(
                    i,
                    prefix,
                    path + (j,),
                    set(range(len(node["fields"]))) if require_all else needed,
                )
                for j, i in enumerate(node["inputs"])
            ]
            if any((len(rows) != 1 for rows in children)):
                raise Unsupported("mux child multiplicity")
            return self.merge([r[0] for r in children], node, prefix, path, require_all)
        width = len(self.nodes[str(node["inputs"][0])]["fields"])
        refs = set(needed)
        refs |= references(node["condition"])
        need_left = {i for i in refs if i < width}
        need_right = {i - width for i in refs if i >= width}
        left = self.build(node["inputs"][0], prefix, path + (0,), need_left)
        right = self.build(node["inputs"][1], prefix, path + (1,), need_right)
        if len(left) != 1 or len(right) != 1:
            raise Unsupported("mux child multiplicity")
        l, r = (left[0], right[0])
        present = z3.Bool(f"{prefix}_{node_id}_{path}_matched")
        if node["join_type"] == "LEFT":
            optional = Cells(
                len(r.cells),
                lambda i: Cell(
                    r.cells[i].value, z3.Or(z3.Not(present), r.cells[i].null)
                ),
            )
            cells = l.cells + optional
            valid = z3.And(
                l.valid,
                z3.Implies(
                    present,
                    z3.And(r.valid, truth(node["condition"], l.cells + r.cells)),
                ),
            )
            bases = l.bases + gate_bases(r.bases, present)
            tags = l.outers + gate_tags(r.outers, present)
            prov = ("optional", role, l.provenance, r.provenance, present)
            tags.append((role, l.provenance, present, z3.BoolVal(True)))
        else:
            optional = Cells(
                len(l.cells),
                lambda i: Cell(
                    l.cells[i].value, z3.Or(z3.Not(present), l.cells[i].null)
                ),
            )
            cells = optional + r.cells
            valid = z3.And(
                r.valid,
                z3.Implies(
                    present,
                    z3.And(l.valid, truth(node["condition"], l.cells + r.cells)),
                ),
            )
            bases = gate_bases(l.bases, present) + r.bases
            tags = gate_tags(l.outers, present) + r.outers
            prov = ("optional", role, r.provenance, l.provenance, present)
            tags.append((role, r.provenance, present, z3.BoolVal(True)))
        return [Row(cells, valid, prov, bases, tags)]

    def consistency(self, rows):
        bases = []
        for row in rows:
            for base in row.bases:
                old = next((x for x in bases if z3.eq(x["rid"], base["rid"])), None)
                if old is None:
                    bases.append(dict(base))
                else:
                    old["present"] = z3.Or(
                        boolean(old.get("present", True)),
                        boolean(base.get("present", True)),
                    )
        constraints = []
        for a, b in combinations(bases, 2):
            if time.perf_counter_ns() > self.deadline:
                raise Budget()
            if a["table"] != b["table"]:
                continue
            both = z3.And(
                boolean(a.get("present", True)), boolean(b.get("present", True))
            )
            active = a["active"] & b["active"]
            constraints.append(
                z3.Implies(
                    z3.And(both, a["rid"] == b["rid"]),
                    land([eq(a["cells"][i], b["cells"][i]) for i in sorted(active)]),
                )
            )
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
                    constraints.append(
                        z3.Implies(z3.And(both, equal), a["rid"] == b["rid"])
                    )
        tags = [t for row in rows for t in row.outers]
        for a, b in combinations(tags, 2):
            if a[0] != b[0]:
                continue
            active = z3.And(
                boolean(a[3]) if len(a) > 3 else z3.BoolVal(True),
                boolean(b[3]) if len(b) > 3 else z3.BoolVal(True),
            )
            constraints.append(
                z3.Implies(
                    z3.And(active, same_provenance(a[1], b[1])),
                    boolean(a[2]) == boolean(b[2]),
                )
            )
        return constraints
