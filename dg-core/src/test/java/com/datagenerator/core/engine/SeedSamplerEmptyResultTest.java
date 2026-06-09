package com.datagenerator.core.engine;

import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.SeedDefinition;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SeedSamplerEmptyResultTest {

    @Test
    void emptySeedQuery_generatesRowsWithNullSeedFields() {
        DataReader emptyReader = new DataReader() {
            @Override
            public String type() {
                return "postgresql";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.empty();
            }

            @Override
            public void close() {
            }
        };

        PluginRegistry pluginRegistry = new PluginRegistry(
                new com.datagenerator.core.reference.ReferenceDataLoader(Map.of("postgresql", emptyReader)));
        TableGeneratorTest.CollectingWriter writer = new TableGeneratorTest.CollectingWriter();
        pluginRegistry.registerWriter("mock", writer);
        TableGenerator tableGenerator = new TableGenerator(pluginRegistry);

        SeedDefinition emptySeed = new SeedDefinition();
        emptySeed.setName("wf_dict_sample");
        emptySeed.setReader(Map.of("type", "postgresql", "query", "SELECT 1 WHERE false"));

        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("t");
        schema.setFields(List.of(
                new FieldDefinition("id", "BIGINT", Map.of("strategy", "sequence", "start", 1, "step", 1)),
                new FieldDefinition(
                        "wfxw",
                        "VARCHAR",
                        Map.of("strategy", "seed", "source", "wf_dict_sample", "field", "wfxw"))));

        TableGenerationResult result = tableGenerator.generate(
                schema,
                3,
                List.of(),
                pluginRegistry.getConstraintRegistry(),
                Map.of(),
                writer,
                List.of(emptySeed),
                GenerationOptions.defaults());

        assertThat(result.failedRows()).isZero();
        assertThat(result.generatedRows()).hasSize(3);
        assertThat(result.generatedRows()).allMatch(row -> row.get("wfxw") == null);
    }
}
