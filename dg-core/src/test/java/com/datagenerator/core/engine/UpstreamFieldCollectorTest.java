package com.datagenerator.core.engine;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.spi.model.DataRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamFieldCollectorTest {

    @Test
    void collectRequiredFields_includesReferenceAndForeignKeyFields() {
        SchemaDefinition downstream = new SchemaDefinition();
        downstream.setFields(List.of(
                new FieldDefinition(
                        "xh",
                        "VARCHAR",
                        Map.of("strategy", "reference", "source", "upstream", "field", "xh", "align", "index")),
                new FieldDefinition(
                        "extra",
                        "VARCHAR",
                        Map.of("strategy", "enum", "values", List.of("a")))));

        ConstraintDefinition fk = new ConstraintDefinition();
        fk.setType("foreign_key");
        fk.setRefTable("upstream");
        fk.setRefField("wfbh");

        Set<String> fields = UpstreamFieldCollector.collectRequiredFields(
                "upstream", downstream, List.of(fk));

        assertThat(fields).containsExactlyInAnyOrder("xh", "wfbh");
    }

    @Test
    void slimRows_keepsOnlyRequiredFields() {
        List<DataRow> rows = List.of(new DataRow(Map.of("xh", "1", "wfbh", "2", "ignored", "x")));
        List<DataRow> slim = UpstreamFieldCollector.slimRows(rows, Set.of("xh", "wfbh"));

        assertThat(slim.getFirst().getFields()).containsOnlyKeys("xh", "wfbh");
        assertThat(slim.getFirst().get("xh")).isEqualTo("1");
    }
}
