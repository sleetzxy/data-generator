package com.datagenerator.core.generator;

import com.datagenerator.spi.model.GenerationContext;
import com.mifmif.common.regex.Generex;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegexGenerator extends AbstractValueGenerator {

    private final ConcurrentHashMap<String, Generex> generexCache = new ConcurrentHashMap<>();

    public RegexGenerator() {
        super("regex");
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        String pattern = String.valueOf(config.get("pattern"));
        if (pattern == null || pattern.isBlank() || "null".equals(pattern)) {
            throw new IllegalArgumentException("regex generator requires 'pattern'");
        }
        Generex generex = generexCache.computeIfAbsent(pattern, Generex::new);
        return generex.random();
    }
}
