package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.YamlConfigLoader;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JobOrchestratorTest {

    private JobOrchestrator orchestrator;
    private CollectingWriter writer;

    @BeforeEach
    void setUp() {
        YamlConfigLoader configLoader =
                new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
        ConstraintLoader constraintLoader = new ConstraintLoader(configLoader);
        PluginRegistry pluginRegistry = new PluginRegistry();
        writer = new CollectingWriter();
        pluginRegistry.registerWriter("mock", writer);
        orchestrator = new JobOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(pluginRegistry),
                pluginRegistry,
                new ConnectionRegistry());
    }

    @Test
    void jobOrchestrator_multiTableWithForeignKeyReference() {
        var job = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()))
                .loadJob("fixtures/jobs/multi_table.yaml");

        JobResult result = orchestrator.run(
                job,
                Map.of("type", "mock", "mode", "insert"),
                GenerationOptions.defaults());

        assertThat(result.totalRows()).isEqualTo(15);
        assertThat(result.writtenRows()).isEqualTo(15);
        assertThat(result.failedRows()).isZero();
        assertThat(result.details()).hasSize(2);
        assertThat(result.details().stream().map(TableResult::table)).containsExactly("customers", "orders");

        List<DataRow> customers = writer.rowsByTable("customers");
        List<DataRow> orders = writer.rowsByTable("orders");
        assertThat(customers).hasSize(5);
        assertThat(orders).hasSize(10);

        Set<Object> customerIds = customers.stream().map(row -> row.get("id")).collect(Collectors.toSet());
        assertThat(orders).allMatch(row -> customerIds.contains(row.get("customer_id")));
    }

    @Test
    void jobOrchestrator_multiWrite_writesToAllTargets() {
        CollectingWriter pgWriter = new CollectingWriter();
        CollectingWriter ckWriter = new CollectingWriter();
        PluginRegistry pluginRegistry = new PluginRegistry();
        pluginRegistry.registerWriter("mock-pg", pgWriter);
        pluginRegistry.registerWriter("mock-ck", ckWriter);
        YamlConfigLoader configLoader =
                new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
        JobOrchestrator multiWriteOrchestrator = new JobOrchestrator(
                configLoader,
                new ConstraintLoader(configLoader),
                new TableGenerator(pluginRegistry),
                pluginRegistry,
                new ConnectionRegistry());

        var job = configLoader.loadJob("fixtures/jobs/multi_table.yaml");
        job.setWriters(List.of(
                Map.of("type", "mock-pg", "mode", "insert"),
                Map.of("type", "mock-ck", "mode", "insert")));

        JobResult result = multiWriteOrchestrator.run(
                job, List.<Map<String, Object>>of(), GenerationOptions.defaults());

        assertThat(result.writtenRows()).isEqualTo(15);
        assertThat(pgWriter.rowsByTable("customers")).hasSize(5);
        assertThat(ckWriter.rowsByTable("customers")).hasSize(5);
        assertThat(pgWriter.rowsByTable("orders")).hasSize(10);
        assertThat(ckWriter.rowsByTable("orders")).hasSize(10);
    }

    @Test
    void jobOrchestrator_jobWriterOverridesRuntimeWriter() {
        var job = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()))
                .loadJob("fixtures/jobs/multi_table.yaml");
        job.setWriter(Map.of("type", "mock", "mode", "insert"));

        JobResult result = orchestrator.run(
                job,
                Map.of("type", "postgresql", "mode", "insert"),
                GenerationOptions.defaults());

        assertThat(result.writtenRows()).isEqualTo(15);
        assertThat(result.failedRows()).isZero();
    }

    static final class CollectingWriter implements DataWriter {

        private final List<Map.Entry<String, DataRow>> rows = new ArrayList<>();

        @Override
        public String type() {
            return "mock";
        }

        @Override
        public void init(WriterConfig config) {
        }

        @Override
        public WriteResult write(Batch batch) {
            for (DataRow row : batch.rows()) {
                rows.add(Map.entry(batch.tableName(), row));
            }
            return new WriteResult(batch.rows().size(), 0);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<DataRow> rowsByTable(String tableName) {
            return rows.stream()
                    .filter(entry -> tableName.equals(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
        }
    }
}
