package com.datagenerator.core.constraint.field;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.DataRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForeignKeyIndexTest {

    @Test
    void build_indexLookup_isO1() {
        List<DataRow> customers = List.of(
                new DataRow(Map.of("id", "a")),
                new DataRow(Map.of("id", "b")));
        ConstraintDefinition fk = foreignKey("customer_id", "customers", "id");
        ForeignKeyIndex index = ForeignKeyIndex.build(Map.of("customers", customers), List.of(fk));

        assertThat(index.contains("customers", "id", "a")).isTrue();
        assertThat(index.contains("customers", "id", "c")).isFalse();
    }

    @Test
    void validator_withIndex_largeUpstream_completesQuickly() {
        int rowCount = 50_000;
        List<DataRow> upstream = new java.util.ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            upstream.add(new DataRow(Map.of("id", "v" + i)));
        }
        ConstraintDefinition fk = foreignKey("ref_id", "upstream", "id");
        ForeignKeyIndex index = ForeignKeyIndex.build(Map.of("upstream", upstream), List.of(fk));
        ForeignKeyValidator validator = new ForeignKeyValidator();

        long start = System.nanoTime();
        for (int i = 0; i < rowCount; i++) {
            DataRow row = new DataRow(Map.of("ref_id", "v" + i));
            var result = validator.validate(
                    new ConstraintContext(row, Map.of("upstream", upstream), Map.of(ForeignKeyIndex.BINDING_KEY, index)),
                    Map.of("field", "ref_id", "ref_table", "upstream", "ref_field", "id"));
            assertThat(result.isValid()).isTrue();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(2_000);
    }

    private static ConstraintDefinition foreignKey(String field, String refTable, String refField) {
        ConstraintDefinition definition = new ConstraintDefinition();
        definition.setLevel("field");
        definition.setField(field);
        definition.setType("foreign_key");
        definition.setRefTable(refTable);
        definition.setRefField(refField);
        return definition;
    }
}
