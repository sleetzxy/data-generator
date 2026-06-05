package com.datagenerator.core.engine;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableGeneratorTest {

    private PluginRegistry pluginRegistry;
    private TableGenerator tableGenerator;
    private CollectingWriter writer;

    @BeforeEach
    void setUp() {
        pluginRegistry = new PluginRegistry();
        tableGenerator = new TableGenerator(pluginRegistry);
        writer = new CollectingWriter();
        pluginRegistry.registerWriter("mock", writer);
    }

    @Test
    void tableGenerator_producesRowsWithConstraints() {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("items");
        schema.setFields(List.of(
                new FieldDefinition("id", "BIGINT", Map.of("strategy", "sequence", "start", 1, "step", 1)),
                new FieldDefinition("score", "INT", Map.of("strategy", "random", "type", "int", "min", 1, "max", 10))));

        ConstraintDefinition range = new ConstraintDefinition();
        range.setLevel("field");
        range.setField("score");
        range.setType("range");
        range.setMin(1.0);
        range.setMax(10.0);

        TableGenerationResult result = tableGenerator.generate(
                schema,
                5,
                List.of(range),
                pluginRegistry.getConstraintRegistry(),
                Map.of(),
                writer,
                GenerationOptions.defaults());

        assertThat(result.generatedRows()).hasSize(5);
        assertThat(result.writtenRows()).isEqualTo(5);
        assertThat(result.failedRows()).isZero();
        assertThat(writer.rows()).hasSize(5);
        assertThat(writer.rows().stream().map(row -> row.get("id")).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void tableGenerator_countsFailedRowsAfterMaxRetries() {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("items");
        schema.setFields(List.of(
                new FieldDefinition("amount", "DECIMAL", Map.of("strategy", "enum", "values", List.of(999)))));

        ConstraintDefinition range = new ConstraintDefinition();
        range.setLevel("field");
        range.setField("amount");
        range.setType("range");
        range.setMin(1.0);
        range.setMax(10.0);

        TableGenerationResult result = tableGenerator.generate(
                schema,
                3,
                List.of(range),
                pluginRegistry.getConstraintRegistry(),
                Map.of(),
                writer,
                new GenerationOptions(1000, 1));

        assertThat(result.generatedRows()).isEmpty();
        assertThat(result.failedRows()).isEqualTo(3);
        assertThat(writer.rows()).isEmpty();
    }

    @Test
    void tableGenerator_resolvesForeignKeyFromUpstream() {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("orders");
        schema.setFields(List.of(
                new FieldDefinition("id", "BIGINT", Map.of("strategy", "sequence", "start", 1, "step", 1)),
                new FieldDefinition(
                        "customer_id",
                        "BIGINT",
                        Map.of("strategy", "reference", "source", "customers", "field", "id"))));

        ConstraintDefinition foreignKey = new ConstraintDefinition();
        foreignKey.setLevel("field");
        foreignKey.setField("customer_id");
        foreignKey.setType("foreign_key");
        foreignKey.setRefTable("customers");
        foreignKey.setRefField("id");

        List<DataRow> customers = List.of(
                new DataRow(Map.of("id", 1L)),
                new DataRow(Map.of("id", 2L)));

        TableGenerationResult result = tableGenerator.generate(
                schema,
                4,
                List.of(foreignKey),
                pluginRegistry.getConstraintRegistry(),
                Map.of("customers", customers),
                writer,
                GenerationOptions.defaults());

        assertThat(result.generatedRows()).hasSize(4);
        assertThat(result.failedRows()).isZero();
        assertThat(result.generatedRows())
                .allMatch(row -> row.get("customer_id").equals(1L) || row.get("customer_id").equals(2L));
    }

    static final class CollectingWriter implements DataWriter {

        private final List<DataRow> rows = new ArrayList<>();

        @Override
        public String type() {
            return "mock";
        }

        @Override
        public void init(WriterConfig config) {
        }

        @Override
        public WriteResult write(Batch batch) {
            rows.addAll(batch.rows());
            return new WriteResult(batch.rows().size(), 0);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<DataRow> rows() {
            return rows;
        }
    }
}
