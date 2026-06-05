package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratorsTest {

    private static GenerationContext emptyContext() {
        return new GenerationContext("test", 0, Map.of(), new DataRow());
    }

    @Test
    void sequence_increments() {
        var gen = new SequenceGenerator();
        var ctx = emptyContext();
        assertThat(gen.generate(ctx, Map.of("start", 100, "step", 1))).isEqualTo(100L);
        assertThat(gen.generate(ctx, Map.of("start", 100, "step", 1))).isEqualTo(101L);
    }

    @Test
    void enum_picksFromList() {
        var gen = new EnumGenerator();
        Object val = gen.generate(emptyContext(), Map.of("values", List.of("A", "B")));
        assertThat(val).isIn("A", "B");
    }

    @Test
    void regex_generatesMatchingString() {
        var gen = new RegexGenerator();
        String val = (String) gen.generate(emptyContext(), Map.of("pattern", "[0-9]{4}"));
        assertThat(val).matches("[0-9]{4}");
    }

    @Test
    void random_generatesIntInRange() {
        var gen = new RandomGenerator();
        int val = (Integer) gen.generate(emptyContext(), Map.of("type", "int", "min", 1, "max", 10));
        assertThat(val).isBetween(1, 10);
    }

    @Test
    void random_generatesDateTimeInRange() {
        var gen = new RandomGenerator();
        var val = (java.time.LocalDateTime) gen.generate(
                emptyContext(),
                Map.of(
                        "type", "datetime",
                        "min", "2024-10-01 00:00:00",
                        "max", "2024-12-31 23:59:59"));
        assertThat(val).isAfterOrEqualTo(java.time.LocalDateTime.parse("2024-10-01T00:00:00"));
        assertThat(val).isBeforeOrEqualTo(java.time.LocalDateTime.parse("2024-12-31T23:59:59"));
    }

    @Test
    void random_generatesFixedDateTimeWhenMinEqualsMax() {
        var gen = new RandomGenerator();
        var val = (java.time.LocalDateTime) gen.generate(
                emptyContext(),
                Map.of(
                        "type", "datetime",
                        "min", "2024-06-26 00:00:00",
                        "max", "2024-06-26 00:00:00"));
        assertThat(val).isEqualTo(java.time.LocalDateTime.parse("2024-06-26T00:00:00"));
    }

    @Test
    void registry_resolvesByStrategy() {
        var registry = new GeneratorRegistry();
        assertThat(registry.get("sequence")).isInstanceOf(SequenceGenerator.class);
        assertThat(registry.get("enum")).isInstanceOf(EnumGenerator.class);
    }

    @Test
    void reference_throwsWhenLoaderNull() {
        var gen = new ReferenceGenerator(null);
        assertThatThrownBy(() -> gen.generate(emptyContext(), Map.of("source", "regions")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reference_picksFromLoadedValues() {
        DataReader mockReader = new DataReader() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public void init(ReaderConfig config) {
            }

            @Override
            public Stream<DataRow> read(ReadRequest request) {
                return Stream.of(
                        new DataRow(Map.of("code", "BJ")),
                        new DataRow(Map.of("code", "SH"))
                );
            }

            @Override
            public void close() {
            }
        };
        var loader = new ReferenceDataLoader(Map.of("regions", mockReader));
        var gen = new ReferenceGenerator(loader);
        Object val = gen.generate(emptyContext(), Map.of("source", "regions", "field", "code"));
        assertThat(val).isIn("BJ", "SH");
    }
}
