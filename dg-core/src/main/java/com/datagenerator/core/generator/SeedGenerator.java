package com.datagenerator.core.generator;

import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;

/**
 * 从 Job 级 seed 采样结果中复制字段值。
 */
public class SeedGenerator extends AbstractValueGenerator {

    public SeedGenerator() {
        super("seed");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        String source = require(config, "source");
        String fieldName = String.valueOf(config.get("field"));
        DataRow seedRow = ctx.seedSamples().get(source);
        if (seedRow == null || !seedRow.getFields().containsKey(fieldName)) {
            return null;
        }
        return seedRow.get(fieldName);
    }

    private static String require(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("strategy seed requires '" + key + "'");
        }
        return String.valueOf(value);
    }
}
