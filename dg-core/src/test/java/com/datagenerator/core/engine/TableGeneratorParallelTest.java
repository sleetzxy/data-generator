package com.datagenerator.core.engine;

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

class TableGeneratorParallelTest {

    private PluginRegistry pluginRegistry;
    private CollectingWriter writer;
    private TableGenerator tableGenerator;

    @BeforeEach
    void setUp() {
        pluginRegistry = new PluginRegistry();
        writer = new CollectingWriter();
        pluginRegistry.registerWriter("mock", writer);
        tableGenerator = new TableGenerator(pluginRegistry);
    }

    @Test
    void generate_largeCountWithParallelism_producesExpectedRows() {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("items");
        schema.setFields(List.of(
                new FieldDefinition("id", "BIGINT", Map.of("strategy", "sequence", "start", 1, "step", 1)),
                new FieldDefinition("tag", "VARCHAR", Map.of("strategy", "enum", "values", List.of("a", "b")))));

        GenerationOptions options = new GenerationOptions(1000, 0, "reject", 4);
        TableGenerationResult result = tableGenerator.generate(
                schema,
                12_000,
                List.of(),
                pluginRegistry.getConstraintRegistry(),
                Map.of(),
                writer,
                List.of(),
                options);

        assertThat(result.generatedRows()).hasSize(12_000);
        assertThat(result.failedRows()).isZero();
        assertThat(writer.rows()).hasSize(12_000);
        assertThat(result.generatedRows().getFirst().get("id")).isEqualTo(1L);
        assertThat(result.generatedRows().get(11_999).get("id")).isEqualTo(12_000L);
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
