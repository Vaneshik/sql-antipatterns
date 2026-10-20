package io.github.vaneshik.sqlantipatterns;

import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.util.ImmutableBitSet;

final class DemoSchema {
    static SchemaPlus create() {
        SchemaPlus schema = Frameworks.createRootSchema(true).add("public", new AbstractSchema());
        schema.add("customer", new DemoTable(factory -> factory.builder()
                .add("id", SqlTypeName.INTEGER).nullable(false)
                .add("name", SqlTypeName.VARCHAR, 100).nullable(true)
                .build()));
        schema.add("orders", new DemoTable(factory -> factory.builder()
                .add("id", SqlTypeName.INTEGER).nullable(false)
                .add("customer_id", SqlTypeName.INTEGER).nullable(true)
                .add("status", SqlTypeName.VARCHAR, 20).nullable(true)
                .build()));
        return schema;
    }

    private static final class DemoTable extends AbstractTable {
        private final RelProtoDataType rowType;

        private DemoTable(RelProtoDataType rowType) {
            this.rowType = rowType;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory factory) {
            return rowType.apply(factory);
        }

        @Override
        public Statistic getStatistic() {
            return Statistics.of(null, List.of(ImmutableBitSet.of(0)), List.of(), List.of());
        }
    }
}
