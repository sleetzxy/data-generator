package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;

/**
 * 声明式占位策略：字段值由 seed.reference / seed.template 提供，不参与 mutate 覆盖。
 */
public class SeedGenerator extends AbstractValueGenerator {

    public SeedGenerator() {
        super("seed");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        throw new IllegalStateException(
                "strategy: seed 字段由 seed 模板填充，请勿列入 seed.mutate");
    }
}
