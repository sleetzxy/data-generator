package com.datagenerator.core.engine;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.SeedDefinition;
import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.reader.DataReader;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SeedRowSnapshotIntegrationTest {

    @Test
    void downstreamTable_reusesRowSeedSnapshotWithoutResampling() {
        AtomicInteger sampleRowCalls = new AtomicInteger();
        DataReader reader = new DataReader() {
            @Override
            public String type() {
                return "postgresql";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                List<DataRow> rows = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    rows.add(new DataRow(Map.of("roadclid", "R" + i, "jd", "113." + i)));
                }
                return rows.stream();
            }

            @Override
            public void close() {
            }
        };

        ReferenceDataLoader loader = new ReferenceDataLoader(Map.of("postgresql", reader)) {
            @Override
            public DataRow sampleRow(String source, Map<String, Object> config) {
                sampleRowCalls.incrementAndGet();
                return super.sampleRow(source, config);
            }
        };

        SeedDefinition roadSeed = new SeedDefinition();
        roadSeed.setName("road_sample");
        roadSeed.setReader(Map.of("type", "postgresql", "query", "SELECT roadclid, jd FROM roads"));

        PluginRegistry pluginRegistry = new PluginRegistry(loader);
        CollectingWriter writer = new CollectingWriter();
        pluginRegistry.registerWriter("mock", writer);
        TableGenerator tableGenerator = new TableGenerator(pluginRegistry);

        SchemaDefinition upstreamSchema = schemaWithSeed("roadclid", "road_sample", "roadclid");
        SchemaDefinition downstreamSchema = schemaWithSeed("roadclid", "road_sample", "roadclid");

        SeedRowSnapshotStore snapshots = new SeedRowSnapshotStore();
        tableGenerator.generate(
                upstreamSchema,
                3,
                List.of(),
                pluginRegistry.getConstraintRegistry(),
                Map.of(),
                writer,
                List.of(roadSeed),
                GenerationOptions.defaults(),
                BatchWrittenCallback.NOOP,
                snapshots);

        int callsAfterUpstream = sampleRowCalls.get();
        assertThat(callsAfterUpstream).isEqualTo(3);

        List<DataRow> upstreamRows = new ArrayList<>(writer.rows());
        writer.clear();

        tableGenerator.generate(
                downstreamSchema,
                3,
                List.of(),
                pluginRegistry.getConstraintRegistry(),
                Map.of("upstream", upstreamRows),
                writer,
                List.of(roadSeed),
                GenerationOptions.defaults(),
                BatchWrittenCallback.NOOP,
                snapshots);

        assertThat(sampleRowCalls.get()).isEqualTo(callsAfterUpstream);
        assertThat(writer.rows()).hasSize(3);
        assertThat(writer.rows().get(0).get("roadclid")).isEqualTo(upstreamRows.get(0).get("roadclid"));
        assertThat(writer.rows().get(1).get("roadclid")).isEqualTo(upstreamRows.get(1).get("roadclid"));
    }

    @Test
    void seedSampler_rowSnapshot_returnsSameSeedRowInstance() {
        SeedDefinition roadSeed = new SeedDefinition();
        roadSeed.setName("road_sample");
        roadSeed.setTemplate(Map.of("roadclid", "R-fixed", "jd", "113.0"));

        SchemaDefinition schema = schemaWithSeed("roadclid", "road_sample", "roadclid");
        SeedSampler sampler = new SeedSampler(
                new ReferenceDataLoader(Map.of()),
                null,
                List.of(roadSeed));
        SeedRowSnapshotStore store = new SeedRowSnapshotStore();

        Map<String, DataRow> first = sampler.sample(schema, 0, store);
        Map<String, DataRow> second = sampler.sample(schema, 0, store);

        assertThat(second.get("road_sample")).isSameAs(first.get("road_sample"));
    }

    private static SchemaDefinition schemaWithSeed(String fieldName, String seedName, String seedField) {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("t");
        schema.setFields(List.of(new FieldDefinition(
                fieldName,
                "VARCHAR",
                Map.of("strategy", "seed", "source", seedName, "field", seedField))));
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

        void clear() {
            rows.clear();
        }
    }
}
