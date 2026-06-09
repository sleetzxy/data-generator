package com.datagenerator.core.generator;

import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedGeneratorTest {

    @Test
    void generate_withSourceAndField_returnsColumnValue() {
        SeedGenerator generator = new SeedGenerator();
        DataRow header = new DataRow(Map.of("customer_name", "Alice"));
        GenerationContext context = new GenerationContext(
                "export",
                0,
                Map.of(),
                new DataRow(),
                Map.of("header", header));

        Object value = generator.generate(
                context, Map.of("strategy", "seed", "source", "header", "field", "customer_name"));

        assertThat(value).isEqualTo("Alice");
    }

    @Test
    void generate_nullColumnValue_returnsNull() {
        SeedGenerator generator = new SeedGenerator();
        DataRow row = new DataRow();
        row.set("pcsid", null);
        GenerationContext context = new GenerationContext(
                "export",
                0,
                Map.of(),
                new DataRow(),
                Map.of("road_sample", row));

        Object value = generator.generate(
                context, Map.of("strategy", "seed", "source", "road_sample", "field", "pcsid"));

        assertThat(value).isNull();
    }

    @Test
    void generate_missingColumn_returnsNull() {
        SeedGenerator generator = new SeedGenerator();
        GenerationContext context = new GenerationContext(
                "export",
                0,
                Map.of(),
                new DataRow(),
                Map.of("header", new DataRow(Map.of("id", 1L))));

        Object value = generator.generate(
                context, Map.of("strategy", "seed", "source", "header", "field", "missing"));

        assertThat(value).isNull();
    }

    @Test
    void generate_missingSeedSample_returnsNull() {
        SeedGenerator generator = new SeedGenerator();
        GenerationContext context = new GenerationContext("export", 0, Map.of(), new DataRow(), Map.of());

        Object value = generator.generate(
                context, Map.of("strategy", "seed", "source", "header", "field", "id"));

        assertThat(value).isNull();
    }
}
