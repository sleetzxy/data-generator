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
        String source = String.valueOf(config.get("source"));
        if (source == null || source.isBlank() || "null".equals(source)) {
            throw new IllegalArgumentException("reference generator requires 'source'");
        }
        String distribution = String.valueOf(config.getOrDefault("distribution", "uniform"));
        if (!"uniform".equalsIgnoreCase(distribution)) {
            throw new UnsupportedOperationException("Unsupported distribution: " + distribution);
        }

        Object upstreamValue = pickFromUpstream(ctx, config, source);
        if (upstreamValue != null) {
            return upstreamValue;
        }

        if (loader == null) {
            throw new IllegalStateException("ReferenceDataLoader is not configured");
        }
        List<Object> values = loader.load(source, config);
        if (values.isEmpty()) {
            throw new IllegalStateException("No reference values loaded for source: " + source);
        }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private Object pickFromUpstream(GenerationContext ctx, Map<String, Object> config, String source) {
        List<com.datagenerator.spi.model.DataRow> upstreamRows = ctx.upstreamTables().get(source);
        if (upstreamRows == null || upstreamRows.isEmpty()) {
            return null;
        }
        Object field = config.get("field");
        if (field == null || String.valueOf(field).isBlank()) {
            throw new IllegalArgumentException("reference generator requires 'field' when using upstream source");
        }
        List<Object> values = upstreamRows.stream()
                .map(row -> row.get(String.valueOf(field)))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException("No values in upstream table " + source + " for field " + field);
        }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
