package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.model.DataRow;
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
        if (!"uniform".equalsIgnoreCase(distribution) && loader == null) {
            throw new IllegalStateException("ReferenceDataLoader is not configured");
        }

        if (ctx.upstreamTables().containsKey(source)) {
            return pickFromUpstream(ctx, config, source);
        }

        if (loader == null) {
            throw new IllegalStateException("ReferenceDataLoader is not configured");
        }
        return loader.sample(source, config);
    }

    private Object pickFromUpstream(GenerationContext ctx, Map<String, Object> config, String source) {
        List<DataRow> upstreamRows = ctx.upstreamTables().get(source);
        if (upstreamRows == null || upstreamRows.isEmpty()) {
            throw new IllegalStateException("Upstream table '" + source + "' produced no rows");
        }
        Object field = config.get("field");
        if (field == null || String.valueOf(field).isBlank()) {
            throw new IllegalArgumentException("reference generator requires 'field' when using upstream source");
        }
        String fieldName = String.valueOf(field);
        String align = String.valueOf(config.getOrDefault("align", "random"));
        if ("index".equalsIgnoreCase(align)) {
            int rowIndex = ctx.rowIndex();
            if (rowIndex >= upstreamRows.size()) {
                throw new IllegalStateException(
                        "Row index " + rowIndex + " out of range for upstream table '"
                                + source + "' (size=" + upstreamRows.size() + ")");
            }
            Object value = upstreamRows.get(rowIndex).get(fieldName);
            if (!upstreamRows.get(rowIndex).getFields().containsKey(fieldName)) {
                return null;
            }
            return value;
        }

        List<Object> values = upstreamRows.stream()
                .map(row -> row.get(fieldName))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
