package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;

public class LiteralGenerator extends AbstractValueGenerator {

    public LiteralGenerator() {
        super("literal");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        if (!config.containsKey("value")) {
            throw new IllegalArgumentException("literal generator requires 'value'");
        }
        return config.get("value");
    }
}
