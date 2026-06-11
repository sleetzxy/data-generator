package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;
import java.util.UUID;

public class UuidGenerator extends AbstractValueGenerator {

    public UuidGenerator() {
        super("uuid");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        UUID uuid = UUID.randomUUID();
        boolean dashed = !Boolean.FALSE.equals(config.get("dashed"));
        return dashed ? uuid.toString() : uuid.toString().replace("-", "");
    }
}
