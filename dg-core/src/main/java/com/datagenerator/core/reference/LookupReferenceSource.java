package com.datagenerator.core.reference;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
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
    private final ConnectionRegistry connectionRegistry;
    private final Map<String, List<Object>> cache = new HashMap<>();
    private final Map<String, List<DataRow>> rowCache = new HashMap<>();

    public LookupReferenceSource(DataReader reader) {
        this(reader, null);
    }

    public LookupReferenceSource(DataReader reader, ConnectionRegistry connectionRegistry) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.connectionRegistry = connectionRegistry;
    }

    public List<Object> load(Map<String, Object> config) {
        String field = requireField(config);
        return cache.computeIfAbsent(field, ignored -> readValues(field, config));
    }

    public List<DataRow> loadRows(Map<String, Object> config) {
        String cacheKey = rowCacheKey(config);
        return rowCache.computeIfAbsent(cacheKey, ignored -> readRows(config));
    }

    private List<Object> readValues(String field, Map<String, Object> config) {
        return readRows(config).stream()
                .map(row -> row.get(field))
                .filter(Objects::nonNull)
                .toList();
    }

    private List<DataRow> readRows(Map<String, Object> config) {
        initReader(config);
        ReadRequest request = buildRequest(config);
        try (var stream = reader.read(request)) {
            List<DataRow> rows = stream.toList();
            if (rows.isEmpty()) {
                throw new IllegalStateException("Reference query returned no rows");
            }
            return rows;
        }
    }

    private void initReader(Map<String, Object> config) {
        Map<String, Object> readerMap = resolveReaderMap(config);
        ReaderConfig resolved = connectionRegistry == null
                ? toReaderConfig(readerMap)
                : connectionRegistry.resolveReader(readerMap);
        reader.init(resolved);
    }

    private static ReadRequest buildRequest(Map<String, Object> config) {
        Object query = config.get("query");
        if (query == null && config.get("reader") instanceof Map<?, ?> readerConfig) {
            query = readerConfig.get("query");
        }
        return query == null ? new ReadRequest(null) : new ReadRequest(String.valueOf(query));
    }

    private static Map<String, Object> resolveReaderMap(Map<String, Object> config) {
        if (config.get("reader") instanceof Map<?, ?> readerConfig) {
            Map<String, Object> readerMap = new HashMap<>();
            readerConfig.forEach((key, value) -> readerMap.put(String.valueOf(key), value));
            if (!readerMap.containsKey("query") && config.get("query") != null) {
                readerMap.put("query", config.get("query"));
            }
            return readerMap;
        }
        Map<String, Object> readerMap = new HashMap<>();
        if (config.get("query") != null) {
            readerMap.put("query", config.get("query"));
        }
        return readerMap;
    }

    private static ReaderConfig toReaderConfig(Map<String, Object> readerMap) {
        return new ReaderConfig(
                asString(readerMap.get("type")),
                asString(readerMap.get("connection")),
                asString(readerMap.get("query")),
                asString(readerMap.get("path")),
                asString(readerMap.get("url")),
                asString(readerMap.get("username")),
                asString(readerMap.get("password")));
    }

    private static String rowCacheKey(Map<String, Object> config) {
        ReadRequest request = buildRequest(config);
        Object connection = config.get("reader") instanceof Map<?, ?> readerConfig
                ? readerConfig.get("connection")
                : config.get("connection");
        return String.valueOf(connection) + ":" + request.query();
    }

    private static String requireField(Map<String, Object> config) {
        Object field = config.get("field");
        if (field == null || String.valueOf(field).isBlank()) {
            throw new IllegalArgumentException("lookup reference requires 'field'");
        }
        return String.valueOf(field);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
