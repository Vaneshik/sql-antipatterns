package io.github.vaneshik.sqlantipatterns;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.util.ImmutableBitSet;

public final class PostgresSchema {
    public static SchemaPlus load(Connection connection, String sql) throws SQLException, SqlParseException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            throw new IllegalArgumentException("MVP поддерживает подключения PostgreSQL.");
        }
        Set<List<String>> tables = new LinkedHashSet<>();
        String queryText = sql.strip();
        if (queryText.endsWith(";")) {
            queryText = queryText.substring(0, queryText.length() - 1);
        }
        SqlParser.create(queryText, SqlParser.config()
                .withUnquotedCasing(Casing.TO_LOWER).withQuotedCasing(Casing.UNCHANGED)
                .withCaseSensitive(true)).parseQuery().accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call instanceof SqlWith) {
                    throw new IllegalArgumentException("CTE пока не поддерживаются загрузчиком метаданных MVP.");
                }
                if (call instanceof SqlSelect select) {
                    collectTables(select.getFrom(), tables);
                }
                return super.visit(call);
            }
        });
        SchemaPlus root = Frameworks.createRootSchema(true);
        for (List<String> name : tables) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Анализ отменён.");
            }
            String qualified = name.stream().map(part -> "\"" + part.replace("\"", "\"\"") + "\"")
                    .collect(java.util.stream.Collectors.joining("."));
            long oid;
            String schemaName;
            String tableName;
            try (var statement = connection.prepareStatement("""
                    SELECT c.oid, n.nspname, c.relname
                    FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.oid = pg_catalog.to_regclass(?)
                    """)) {
                statement.setQueryTimeout(15);
                statement.setString(1, qualified);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException("Таблица не найдена в search_path подключения — " + qualified);
                    }
                    oid = result.getLong(1);
                    schemaName = result.getString(2);
                    tableName = result.getString(3);
                }
            }
            List<Column> columns = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT a.attname, t.typname, a.attnotnull, a.attnum, a.atttypmod, n.nspname
                    FROM pg_catalog.pg_attribute a
                    JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                    WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY a.attnum
                    """)) {
                statement.setQueryTimeout(15);
                statement.setLong(1, oid);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        if (!result.getString(6).equals("pg_catalog")) {
                            throw new IllegalArgumentException("Пользовательские типы PostgreSQL пока не поддерживаются.");
                        }
                        columns.add(new Column(result.getString(1), result.getString(2),
                                result.getBoolean(3), result.getInt(4), result.getInt(5)));
                    }
                }
            }
            List<ImmutableBitSet> keys = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT i.indexrelid, a.attnum, a.attnotnull
                    FROM pg_catalog.pg_index i
                    CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, position)
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                    WHERE i.indrelid = ? AND i.indisunique AND i.indisvalid AND i.indisready
                      AND i.indimmediate AND i.indpred IS NULL AND i.indexprs IS NULL
                      AND k.position <= i.indnkeyatts
                      AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits h WHERE h.inhparent = i.indrelid)
                    ORDER BY i.indexrelid, k.position
                    """)) {
                statement.setQueryTimeout(15);
                statement.setLong(1, oid);
                try (var result = statement.executeQuery()) {
                    long index = -1;
                    List<Integer> positions = new ArrayList<>();
                    boolean strict = true;
                    while (result.next()) {
                        long nextIndex = result.getLong(1);
                        if (nextIndex != index) {
                            if (strict && !positions.isEmpty()) {
                                keys.add(ImmutableBitSet.of(positions));
                            }
                            positions.clear();
                            strict = true;
                            index = nextIndex;
                        }
                        int attributeNumber = result.getInt(2);
                        int position = -1;
                        for (int i = 0; i < columns.size(); i++) {
                            if (columns.get(i).number() == attributeNumber) {
                                position = i;
                                break;
                            }
                        }
                        if (position < 0) {
                            throw new SQLException("Метаданные колонок и ключей изменились. Повторите анализ.");
                        }
                        positions.add(position);
                        strict &= result.getBoolean(3);
                    }
                    if (strict && !positions.isEmpty()) {
                        keys.add(ImmutableBitSet.of(positions));
                    }
                }
            }
            var table = new AbstractTable() {
                @Override
                public RelDataType getRowType(RelDataTypeFactory factory) {
                    var builder = factory.builder();
                    for (Column column : columns) {
                        builder.add(column.name(), column.type(factory)).nullable(!column.notNull());
                    }
                    return builder.build();
                }

                @Override
                public Statistic getStatistic() {
                    return Statistics.of(null, keys, List.of(), List.of());
                }
            };
            SchemaPlus schema = root.getSubSchema(schemaName);
            if (schema == null) {
                schema = root.add(schemaName, new AbstractSchema());
            }
            schema.add(tableName, table);
            if (name.size() == 1) {
                root.add(tableName, table);
            }
        }
        return root;
    }

    private static void collectTables(SqlNode node, Set<List<String>> tables) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlIdentifier identifier) {
            if (identifier.names.size() > 2) {
                throw new IllegalArgumentException("Используйте table или schema.table.");
            }
            tables.add(identifier.names);
        } else if (node instanceof SqlJoin join) {
            collectTables(join.getLeft(), tables);
            collectTables(join.getRight(), tables);
        } else if (node.getKind() == SqlKind.AS) {
            collectTables(((SqlCall) node).operand(0), tables);
        }
    }

    private record Column(String name, String postgresType, boolean notNull, int number, int modifier) {
        RelDataType type(RelDataTypeFactory factory) {
            SqlTypeName type = switch (postgresType) {
                case "int2" -> SqlTypeName.SMALLINT;
                case "int4" -> SqlTypeName.INTEGER;
                case "int8" -> SqlTypeName.BIGINT;
                case "bool" -> SqlTypeName.BOOLEAN;
                case "text", "varchar" -> SqlTypeName.VARCHAR;
                case "bpchar" -> SqlTypeName.CHAR;
                case "numeric" -> SqlTypeName.DECIMAL;
                case "float4" -> SqlTypeName.REAL;
                case "float8" -> SqlTypeName.DOUBLE;
                case "date" -> SqlTypeName.DATE;
                case "timestamp" -> SqlTypeName.TIMESTAMP;
                case "timestamptz" -> SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
                default -> throw new IllegalArgumentException("Тип PostgreSQL пока не поддерживается — " + postgresType);
            };
            if ((type == SqlTypeName.CHAR || type == SqlTypeName.VARCHAR) && modifier >= 4) {
                return factory.createSqlType(type, modifier - 4);
            }
            if (type == SqlTypeName.DECIMAL && modifier >= 4) {
                int precision = ((modifier - 4) >> 16) & 0xffff;
                int scale = (short) ((modifier - 4) & 0xffff);
                if (scale < 0 || scale > precision) {
                    throw new IllegalArgumentException("NUMERIC с таким scale пока не поддерживается.");
                }
                RelDataType decimal = factory.createSqlType(type, precision, scale);
                if (decimal.getPrecision() != precision || decimal.getScale() != scale) {
                    throw new IllegalArgumentException("Точность NUMERIC выходит за пределы типов Calcite.");
                }
                return decimal;
            }
            if (type == SqlTypeName.DECIMAL) {
                throw new IllegalArgumentException("Для NUMERIC в MVP нужны явные precision и scale.");
            }
            if (type == SqlTypeName.TIMESTAMP || type == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE) {
                return factory.createSqlType(type, modifier >= 0 ? modifier : 6);
            }
            return factory.createSqlType(type);
        }
    }
}
