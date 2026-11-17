import sqlglot
from sqlglot import exp


def dialect_bridge(sql):
    """Unmodelled PostgreSQL unknown-string vs Calcite fixed-CHAR coercion is explicit scope."""
    try:
        tree = sqlglot.parse_one(sql, read="postgres")
    except Exception as e:
        return False, str(e)
    for node in tree.walk():
        if isinstance(node, exp.Values) and any(
            isinstance(x, exp.Literal) and x.is_string for x in node.walk()
        ):
            return False, "string VALUES type bridge unsupported"
        if isinstance(
            node,
            (
                exp.EQ,
                exp.NEQ,
                exp.LT,
                exp.LTE,
                exp.GT,
                exp.GTE,
                exp.NullSafeEQ,
                exp.NullSafeNEQ,
            ),
        ):
            if not any(isinstance(x, exp.Column) for x in node.walk()) and any(
                isinstance(x, exp.Literal) and x.is_string for x in node.walk()
            ):
                return False, "constant string comparison type bridge unsupported"
    for select in tree.find_all(exp.Select):
        for expr in select.expressions:
            if any(isinstance(x, exp.Literal) and x.is_string for x in expr.walk()):
                return False, "projected string literal type bridge unsupported"
    return True, "COMPATIBLE_BRIDGE"
