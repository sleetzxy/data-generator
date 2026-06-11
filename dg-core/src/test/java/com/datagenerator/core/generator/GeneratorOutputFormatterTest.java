package com.datagenerator.core.generator;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorOutputFormatterTest {

    @Test
    void apply_noPrefix_returnsOriginalValue() {
        assertThat(GeneratorOutputFormatter.apply(100L, Map.of())).isEqualTo(100L);
    }

    @Test
    void apply_numericWithPrefixAndWidth_zeroPadsBody() {
        Map<String, Object> config = Map.of("prefix", "4401152024", "width", 6);
        assertThat(GeneratorOutputFormatter.apply(1L, config)).isEqualTo("4401152024000001");
    }

    @Test
    void apply_stringBodyWithPrefix_concatenates() {
        Map<String, Object> config = Map.of("prefix", "4401152024");
        assertThat(GeneratorOutputFormatter.apply("123456", config)).isEqualTo("4401152024123456");
    }

    @Test
    void apply_numericWithPrefixWithoutWidth_concatenatesRawNumber() {
        Map<String, Object> config = Map.of("prefix", "ORD-");
        assertThat(GeneratorOutputFormatter.apply(100L, config)).isEqualTo("ORD-100");
    }

    @Test
    void apply_nullValue_returnsNull() {
        Map<String, Object> config = new HashMap<>();
        config.put("prefix", "X-");
        assertThat(GeneratorOutputFormatter.apply(null, config)).isNull();
    }

    @Test
    void apply_nullValueWithDefault_usesDefault() {
        assertThat(GeneratorOutputFormatter.apply(null, Map.of("default", ""))).isEqualTo("");
        assertThat(GeneratorOutputFormatter.apply(null, Map.of("default", "UNKNOWN"))).isEqualTo("UNKNOWN");
    }

    @Test
    void apply_emptyStringWithDefault_usesDefault() {
        assertThat(GeneratorOutputFormatter.apply("", Map.of("default", "FALLBACK"))).isEqualTo("FALLBACK");
    }

    @Test
    void apply_nullValueWithDefaultAndPrefix_formatsDefault() {
        Map<String, Object> config = Map.of("default", 1L, "prefix", "ORD-", "width", 4);
        assertThat(GeneratorOutputFormatter.apply(null, config)).isEqualTo("ORD-0001");
    }
}
