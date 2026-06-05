package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.model.GenerationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class ReferenceGenerator extends AbstractValueGenerator {

    private final ReferenceDataLoader loader;

    public ReferenceGenerator(ReferenceDataLoader loader) {
        super("reference");
        this.loader = loader;
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        if (loader == null) {
            throw new IllegalStateException("ReferenceDataLoader is not configured");
        }
        String source = String.valueOf(config.get("source"));
        if (source == null || source.isBlank() || "null".equals(source)) {
            throw new IllegalArgumentException("reference generator requires 'source'");
        }
        String distribution = String.valueOf(config.getOrDefault("distribution", "uniform"));
        if (!"uniform".equalsIgnoreCase(distribution)) {
            throw new UnsupportedOperationException("Unsupported distribution: " + distribution);
        }
        List<Object> values = loader.load(source, config);
        if (values.isEmpty()) {
            throw new IllegalStateException("No reference values loaded for source: " + source);
        }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
