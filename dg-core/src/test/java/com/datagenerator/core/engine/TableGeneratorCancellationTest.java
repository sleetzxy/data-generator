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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableGeneratorCancellationTest {

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
    void generate_whenCancelledDuringSequentialWrite_stopsEarly() {
        SchemaDefinition schema = simpleSchema();
        CancellationChecker checker = () -> writer.rows().size() >= 500;
        GenerationOptions options = new GenerationOptions(100, 0, "reject", 1, checker);

        assertThatThrownBy(() -> tableGenerator.generate(
                        schema,
                        2_000,
                        List.of(),
                        pluginRegistry.getConstraintRegistry(),
                        Map.of(),
                        writer,
                        List.of(),
                        options))
                .isInstanceOf(JobCancelledException.class);

        assertThat(writer.rows()).hasSizeLessThan(2_000);
        assertThat(writer.rows().size()).isGreaterThanOrEqualTo(500);
    }

    @Test
    void generate_whenCancelledDuringParallelGeneration_stopsEarly() {
        SchemaDefinition schema = simpleSchema();
        AtomicInteger checks = new AtomicInteger();
        CancellationChecker checker = () -> checks.incrementAndGet() > 2;
        GenerationOptions options = new GenerationOptions(1000, 0, "reject", 4, checker);

        assertThatThrownBy(() -> tableGenerator.generate(
                        schema,
                        12_000,
                        List.of(),
                        pluginRegistry.getConstraintRegistry(),
                        Map.of(),
                        writer,
                        List.of(),
                        options))
                .isInstanceOf(JobCancelledException.class);

        assertThat(writer.rows()).hasSizeLessThan(12_000);
    }

    private static SchemaDefinition simpleSchema() {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("items");
        schema.setFields(List.of(
                new FieldDefinition("id", "BIGINT", Map.of("strategy", "sequence", "start", 1, "step", 1)),
                new FieldDefinition("tag", "VARCHAR", Map.of("strategy", "enum", "values", List.of("a", "b")))));
        return schema;
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
