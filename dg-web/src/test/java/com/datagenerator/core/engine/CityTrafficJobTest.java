package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.reader.DataReader;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        ConnectionRegistry connectionRegistry = new ConnectionRegistry(Map.of(
                "dev-road", Map.of(
                        "type", "postgresql",
                        "url", "jdbc:postgresql://localhost:5432/dev",
                        "username", "test",
                        "password", "test"),
                "dev-safety", Map.of(
                        "type", "postgresql",
                        "url", "jdbc:postgresql://localhost:5432/safety",
                        "username", "test",
                        "password", "test"),
                "traffic-output", Map.of(
                        "type", "csv",
                        "path", "./output/traffic")));
        ReferenceDataLoader referenceDataLoader = new ReferenceDataLoader(
                Map.of("postgresql", roadNetworkReader()), connectionRegistry);
        PluginRegistry pluginRegistry = new PluginRegistry(referenceDataLoader);
        List<Map.Entry<String, DataRow>> collectedRows = new ArrayList<>();
        writer = new CollectingWriter("csv", collectedRows);
        pluginRegistry.registerWriter("csv", writer);
        pluginRegistry.registerWriter("postgresql", new CollectingWriter("postgresql", collectedRows));
        orchestrator = new JobOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(pluginRegistry, configLoader),
                pluginRegistry,
                connectionRegistry);
    }

    @Test
    @Disabled("生产 YAML acd_file count=100000，需独立小体量 fixture 后再启用")
    void cityAcdWfJqPreview_generatesLinkedAccidentAndRelatedTables() {
        YamlConfigLoader configLoader = new YamlConfigLoader(ConfigPathResolver.forClasspath(
                getClass().getClassLoader(), "configs"));
        var job = configLoader.loadJob("jobs/city_acd_wf_jq_preview.yaml");

        JobResult result = orchestrator.run(job, Map.of(), GenerationOptions.defaults());

        assertThat(result.totalRows()).isEqualTo(40);
        assertThat(result.writtenRows()).isEqualTo(40);
        assertThat(result.failedRows()).isZero();

        List<DataRow> acdFile = writer.rowsByTable("acd_file_sh");
        List<DataRow> acdHuman = writer.rowsByTable("acd_filehuman");
        List<DataRow> acdSimple = writer.rowsByTable("acd_dutysimple_sh");
        List<DataRow> acdSimpleHuman = writer.rowsByTable("acd_dutysimplehuman");
        List<DataRow> wf = writer.rowsByTable("wf_3y");
        List<DataRow> jq = writer.rowsByTable("jqxx");

        assertThat(acdFile).hasSize(5);
        assertThat(acdHuman).hasSize(10);
        assertThat(acdSimple).hasSize(5);
        assertThat(acdSimpleHuman).hasSize(10);
        assertThat(wf).hasSize(5);
        assertThat(jq).hasSize(5);

        Set<Object> sgbhSet = acdFile.stream().map(row -> row.get("sgbh")).collect(Collectors.toSet());
        assertThat(sgbhSet).isNotEmpty();
        assertThat(acdHuman).allMatch(row -> sgbhSet.contains(row.get("sgbh")));
        Set<Object> simpleSgbhSet = acdSimple.stream().map(row -> row.get("sgbh")).collect(Collectors.toSet());
        assertThat(acdSimpleHuman).allMatch(row -> simpleSgbhSet.contains(row.get("sgbh")));
        assertThat(acdFile.getFirst().get("qdms")).isEqualTo("0");
        assertThat(acdFile.getFirst().get("xzqh")).isEqualTo("440115");
        assertThat(acdFile.getFirst().get("sgfssj")).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(acdFile.getFirst().get("lm")).isEqualTo("番禺大道");
        assertThat(acdSimple.getFirst().get("sgfssj")).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(acdFile.getFirst().get("jd")).isEqualTo(0);
        assertThat(acdFile.getFirst().get("wd")).isEqualTo(0);
        assertThat(acdFile.getFirst().get("my_dt"))
                .isEqualTo(java.time.LocalDateTime.parse("2024-06-26T00:00:00"));
        assertThat(wf.getFirst().get("xzqh")).isEqualTo("440115");
        assertThat(jq.getFirst().get("ssxqdm")).isEqualTo("440115");
    }

    private static DataReader roadNetworkReader() {
        return new DataReader() {
            @Override
            public String type() {
                return "postgresql";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.of(
                        roadRow("300001731000000001", "20241015 14:32:08"),
                        roadRow("300001731000000002", "20241120 08:15:30"),
                        roadRow("300001731000000003", "20241028 19:45:12"),
                        roadRow("300001731000000004", "20241105 11:20:55"),
                        roadRow("300001731000000005", "20241118 06:08:41"),
                        roadRow("300001731000000006", "20241022 16:40:18"),
                        roadRow("300001731000000007", "20241111 09:12:44"),
                        roadRow("300001731000000008", "20241008 21:33:27"),
                        roadRow("300001731000000009", "20241127 13:55:03"),
                        roadRow("300001731000000010", "20241031 07:18:59"));
            }

            @Override
            public void close() {
            }
        };
    }

    private static DataRow roadRow(String sgbh, String sgfssj) {
        DataRow row = new DataRow();
        row.set("geom", "POINT(113.384521 22.974531)");
        row.set("geomwkt", "POINT(113.384521 22.974531)");
        row.set("roadclid", "4401150000001");
        row.set("dllx", "0");
        row.set("fnode", "10001");
        row.set("tnode", "10002");
        row.set("road_type", "8");
        row.set("lm", "番禺大道");
        row.set("xzqh", "440115");
        row.set("sgdd", "番禺大道往东150米");
        row.set("pcs_id", "xq01");
        row.set("pcs_name", "市桥中队");
        return row;
    }

    static final class CollectingWriter implements DataWriter {

        private final String writerType;
        private final List<Map.Entry<String, DataRow>> rows;

        CollectingWriter(String writerType, List<Map.Entry<String, DataRow>> rows) {
            this.writerType = writerType;
            this.rows = rows;
        }

        @Override
        public String type() {
            return writerType;
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
