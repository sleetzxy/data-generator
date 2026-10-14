package com.datagenerator.core.generator;

import com.datagenerator.spi.generator.ValueGenerator;
import com.datagenerator.spi.model.GenerationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorRegistrySpiTest {

    @Test
    void register_extraGenerator_overridesBuiltinStrategy() {
        GeneratorRegistry registry = GeneratorRegistry.withBuiltins(null);
        registry.register(new FixedLiteralGenerator("literal", "from-plugin"));

        assertThat(registry.get("literal").generate(null, Map.of())).isEqualTo("from-plugin");
    }

    @Test
    void emptyRegistry_requiresExplicitRegistration() {
        GeneratorRegistry registry = new GeneratorRegistry();
        registry.register(new FixedLiteralGenerator("custom", "ok"));
        assertThat(registry.get("custom").generate(null, Map.of())).isEqualTo("ok");
    }

    private static final class FixedLiteralGenerator implements ValueGenerator {
        private final String strategy;
        private final String value;

        private FixedLiteralGenerator(String strategy, String value) {
            this.strategy = strategy;
            this.value = value;
        }

        @Override
        public String strategy() {
            return strategy;
        }

        @Override
        public String type() {
            return strategy;
        }

        @Override
        public Object generate(GenerationContext context, Map<String, Object> params) {
            return value;
        }
    }
}
