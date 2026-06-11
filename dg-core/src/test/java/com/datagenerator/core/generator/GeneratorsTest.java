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
        assertThat(gen.generate(new GenerationContext("test", 0, Map.of(), new DataRow()), Map.of("start", 100, "step", 1)))
                .isEqualTo(100L);
        assertThat(gen.generate(new GenerationContext("test", 1, Map.of(), new DataRow()), Map.of("start", 100, "step", 1)))
                .isEqualTo(101L);
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
    void uuid_generatesStandardFormat() {
        var gen = new UuidGenerator();
        String val = (String) gen.generate(emptyContext(), Map.of());
        assertThat(val).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void uuid_withoutDashes_returns32HexChars() {
        var gen = new UuidGenerator();
        String val = (String) gen.generate(emptyContext(), Map.of("dashed", false));
        assertThat(val).matches("[0-9a-f]{32}");
    }

    @Test
    void phone_cn_generatesMobileNumber() {
        var gen = new PhoneGenerator();
        String val = (String) gen.generate(emptyContext(), Map.of("region", "cn"));
        assertThat(val).matches("1[3-9][0-9]{9}");
    }

    @Test
    void email_generatesAddressWithDomain() {
        var gen = new EmailGenerator();
        String val = (String) gen.generate(emptyContext(), Map.of("domain", "test.com", "minLength", 4, "maxLength", 4));
        assertThat(val).isEqualTo(val.toLowerCase());
        assertThat(val).endsWith("@test.com");
        assertThat(val.indexOf('@')).isEqualTo(4);
    }

    @Test
    void literal_returnsConfiguredValue() {
        var gen = new LiteralGenerator();
        assertThat(gen.generate(emptyContext(), Map.of("value", "ACTIVE"))).isEqualTo("ACTIVE");
        assertThat(gen.generate(emptyContext(), Map.of("value", 0))).isEqualTo(0);
    }

    @Test
    void idcard_generatesValid18DigitNumber() {
        var gen = new IdCardGenerator();
        String val = (String) gen.generate(
                emptyContext(),
                Map.of("areaCode", "110101", "birthDate", "1990-01-01", "gender", "male"));
        assertThat(val).hasSize(18);
        assertThat(val).matches("\\d{17}[0-9X]");
        assertThat(val.charAt(16) % 2).isEqualTo(1);
        assertThat(val.charAt(17)).isEqualTo(IdCardGenerator.calculateCheckDigit(val.substring(0, 17)));
    }

    @Test
    void idcard_female_usesEvenSequenceDigit() {
        var gen = new IdCardGenerator();
        String val = (String) gen.generate(
                emptyContext(),
                Map.of("areaCode", "440115", "birthDate", "19950101", "gender", "female"));
        assertThat(val.substring(0, 6)).isEqualTo("440115");
        assertThat(val.substring(6, 14)).isEqualTo("19950101");
        assertThat(val.charAt(16) % 2).isEqualTo(0);
    }

    @Test
    void idcard_withoutAreaCode_picksFromNationwideCodes() {
        var gen = new IdCardGenerator();
        for (int i = 0; i < 20; i++) {
            String val = (String) gen.generate(emptyContext(), Map.of("birthDate", "1990-01-01"));
            assertThat(val).hasSize(18);
            assertThat(val.substring(0, 6)).matches("\\d{6}");
        }
    }

    @Test
    void idcard_randomBirthDateWithinRange() {
        var gen = new IdCardGenerator();
        String val = (String) gen.generate(
                emptyContext(),
                Map.of("birthDateMin", "2000-01-01", "birthDateMax", "2000-01-01"));
        assertThat(val.substring(6, 14)).isEqualTo("20000101");
    }

    @Test
    void registry_resolvesByStrategy() {
        var registry = new GeneratorRegistry();
        assertThat(registry.get("sequence")).isInstanceOf(SequenceGenerator.class);
        assertThat(registry.get("enum")).isInstanceOf(EnumGenerator.class);
        assertThat(registry.get("uuid")).isInstanceOf(UuidGenerator.class);
        assertThat(registry.get("phone")).isInstanceOf(PhoneGenerator.class);
        assertThat(registry.get("email")).isInstanceOf(EmailGenerator.class);
        assertThat(registry.get("literal")).isInstanceOf(LiteralGenerator.class);
        assertThat(registry.get("idcard")).isInstanceOf(IdCardGenerator.class);
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

    @Test
    void reference_alignIndex_picksSameRowFromUpstream() {
        var gen = new ReferenceGenerator(null);
        DataRow upstreamRow0 = new DataRow();
        upstreamRow0.set("xh", "1");
        DataRow upstreamRow1 = new DataRow();
        upstreamRow1.set("xh", "2");
        Map<String, List<DataRow>> upstream = Map.of(
                "parent", List.of(upstreamRow0, upstreamRow1));
        var ctx0 = new GenerationContext("child", 0, upstream, new DataRow());
        var ctx1 = new GenerationContext("child", 1, upstream, new DataRow());
        Map<String, Object> config = Map.of("source", "parent", "field", "xh", "align", "index");

        assertThat(gen.generate(ctx0, config)).isEqualTo("1");
        assertThat(gen.generate(ctx1, config)).isEqualTo("2");
    }

    @Test
    void reference_upstreamEmpty_doesNotFallbackToExternalLoader() {
        var loader = new ReferenceDataLoader(Map.of());
        var gen = new ReferenceGenerator(loader);
        Map<String, List<DataRow>> upstream = Map.of("parent", List.of());
        var ctx = new GenerationContext("child", 0, upstream, new DataRow());

        assertThatThrownBy(() -> gen.generate(ctx, Map.of("source", "parent", "field", "xh")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent");
    }

    @Test
    void expression_evaluatesFromCurrentRowFields() {
        var gen = new ExpressionGenerator();
        DataRow row = new DataRow();
        row.set("xh", "1");
        row.set("wfbh", "4401132023000001");
        row.set("wfxw", "10391");
        var ctx = new GenerationContext("child", 0, Map.of(), row);
        Object value = gen.generate(
                ctx,
                Map.of(
                        "language", "spel",
                        "expression", "xh + '_' + wfbh + '_' + wfxw"));
        assertThat(value).isEqualTo("1_4401132023000001_10391");
    }
}
