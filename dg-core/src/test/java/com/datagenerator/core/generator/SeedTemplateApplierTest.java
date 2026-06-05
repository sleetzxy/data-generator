package com.datagenerator.core.generator;

import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.spi.model.GenerationContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedTemplateApplierTest {

    @Test
    void seedTemplateApplier_mutatesSpecifiedFields() {
        GeneratorRegistry registry = new GeneratorRegistry(null);
        SeedTemplateApplier applier = new SeedTemplateApplier(registry);

        SchemaDefinition schema = new SchemaDefinition();
        schema.setSeed(Map.of(
                "template", Map.of("status", "ACTIVE", "region", "CN"),
                "mutate", List.of("id")));
        FieldDefinition idField = new FieldDefinition();
        idField.setName("id");
        idField.setGenerator(Map.of("strategy", "sequence", "start", 1, "step", 1));
        schema.setFields(List.of(idField));

        var row = applier.apply(schema, new GenerationContext("orders", 0, Map.of(), new com.datagenerator.spi.model.DataRow()));
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("region")).isEqualTo("CN");
        assertThat(row.get("id")).isEqualTo(1L);
    }
}
