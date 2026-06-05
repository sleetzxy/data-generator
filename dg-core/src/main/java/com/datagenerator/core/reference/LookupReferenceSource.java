package com.datagenerator.core.reference;

import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.reader.DataReader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads column values from a {@link DataReader} lookup source, with per-field caching.
 */
public class LookupReferenceSource {

    private final DataReader reader;
    private final Map<String, List<Object>> cache = new HashMap<>();

    public LookupReferenceSource(DataReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public List<Object> load(Map<String, Object> config) {
        String field = requireField(config);
        return cache.computeIfAbsent(field, ignored -> readValues(field, config));
    }

    private List<Object> readValues(String field, Map<String, Object> config) {
        ReadRequest request = buildRequest(config);
        return reader.read(request)
                .map(row -> row.get(field))
                .filter(Objects::nonNull)
                .toList();
    }

    private static ReadRequest buildRequest(Map<String, Object> config) {
        Object query = config.get("query");
        if (query == null && config.get("reader") instanceof Map<?, ?> readerConfig) {
            query = readerConfig.get("query");
        }
        return query == null ? new ReadRequest(null) : new ReadRequest(String.valueOf(query));
    }

    private static String requireField(Map<String, Object> config) {
        Object field = config.get("field");
        if (field == null || String.valueOf(field).isBlank()) {
            throw new IllegalArgumentException("lookup reference requires 'field'");
        }
        return String.valueOf(field);
    }
}
