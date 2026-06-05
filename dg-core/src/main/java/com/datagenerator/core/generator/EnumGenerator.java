package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class EnumGenerator extends AbstractValueGenerator {

    public EnumGenerator() {
        super("enum");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        Object valuesObject = config.get("values");
        if (!(valuesObject instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("enum generator requires non-empty 'values' list");
        }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
