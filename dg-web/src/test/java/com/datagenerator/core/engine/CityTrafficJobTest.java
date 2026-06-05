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

class CityTrafficJobTest {

    private JobOrchestrator orchestrator;
    private CollectingWriter writer;

    @BeforeEach
    void setUp() {
        ConfigPathResolver pathResolver = ConfigPathResolver.forClasspath(
                getClass().getClassLoader(), "configs");
        YamlConfigLoader configLoader = new YamlConfigLoader(pathResolver);
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
    void cityAcdWfJqPreview_generatesLinkedAccidentAndRelatedTables() {
        YamlConfigLoader configLoader = new YamlConfigLoader(ConfigPathResolver.forClasspath(
                getClass().getClassLoader(), "configs"));
        var job = configLoader.loadJob("jobs/city_acd_wf_jq_preview.yaml");

        JobResult result = orchestrator.run(
                job,
                Map.of("type", "mock", "mode", "insert"),
                GenerationOptions.defaults());

        assertThat(result.totalRows()).isEqualTo(25);
        assertThat(result.writtenRows()).isEqualTo(25);
        assertThat(result.failedRows()).isZero();

        List<DataRow> acdFile = writer.rowsByTable("acd_file");
        List<DataRow> acdHuman = writer.rowsByTable("acd_filehuman");
        List<DataRow> wf = writer.rowsByTable("wf_3y");
        List<DataRow> jq = writer.rowsByTable("jqxx");

        assertThat(acdFile).hasSize(5);
        assertThat(acdHuman).hasSize(10);
        assertThat(wf).hasSize(5);
        assertThat(jq).hasSize(5);

        Set<Object> sgbhSet = acdFile.stream().map(row -> row.get("sgbh")).collect(Collectors.toSet());
        assertThat(sgbhSet).hasSize(5);
        assertThat(acdHuman).allMatch(row -> sgbhSet.contains(row.get("sgbh")));
        assertThat(acdFile.getFirst().get("xzqh")).isEqualTo("440115");
        assertThat(wf.getFirst().get("xzqh")).isEqualTo("440115");
        assertThat(jq.getFirst().get("ssxqdm")).isEqualTo("440115");
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
