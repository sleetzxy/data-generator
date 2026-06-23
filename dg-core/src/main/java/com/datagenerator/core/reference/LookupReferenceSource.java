package com.datagenerator.core.reference;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads column values from a {@link DataReader} lookup source, with per-field caching.
 */
public class LookupReferenceSource {

    /** 未显式配置 maxRows 时的默认上限，避免 seed 查询全表 materialize。 */
    public static final int DEFAULT_MAX_ROWS = 50_000;

    private final DataReader reader;
    private final ConnectionRegistry connectionRegistry;
    private final Map<String, List<Object>> cache = new ConcurrentHashMap<>();
    private final Map<String, List<DataRow>> rowCache = new ConcurrentHashMap<>();

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
        int maxRows = resolveMaxRows(config);
        try (var stream = reader.read(request)) {
            Iterator<DataRow> iterator = stream.iterator();
            List<DataRow> rows = new ArrayList<>();
            while (iterator.hasNext() && rows.size() < maxRows) {
                rows.add(iterator.next());
            }
            return List.copyOf(rows);
        }
    }

    private void initReader(Map<String, Object> config) {
        Map<String, Object> readerMap = resolveReaderMap(config);
        ConnectionRegistry registry = connectionRegistry != null
                ? connectionRegistry
                : new ConnectionRegistry();
        reader.init(registry.resolveReader(readerMap));
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

    private static int resolveMaxRows(Map<String, Object> config) {
        Object value = config.get("maxRows");
        if (value == null && config.get("reader") instanceof Map<?, ?> readerConfig) {
            value = readerConfig.get("maxRows");
        }
        if (value == null) {
            return DEFAULT_MAX_ROWS;
        }
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        return Math.max(1, Integer.parseInt(String.valueOf(value)));
    }

    private String rowCacheKey(Map<String, Object> config) {
        ReadRequest request = buildRequest(config);
        Map<String, Object> readerMap = resolveReaderMap(config);
        return connectionCacheKey(readerMap) + ":" + request.query() + ":max=" + resolveMaxRows(config);
    }

    private String connectionCacheKey(Map<String, Object> readerMap) {
        ConnectionRegistry registry = connectionRegistry != null
                ? connectionRegistry
                : new ConnectionRegistry();
        return registry.readerConnectionCacheKey(readerMap);
    }

    private static String requireField(Map<String, Object> config) {
        Object field = config.get("field");
        if (field == null || String.valueOf(field).isBlank()) {
            throw new IllegalArgumentException("lookup reference requires 'field'");
        }
        return String.valueOf(field);
    }
}
