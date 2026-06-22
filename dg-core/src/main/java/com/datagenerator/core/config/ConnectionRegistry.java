package com.datagenerator.core.config;

import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriterConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ConnectionRegistry {

    private static final Set<String> INLINE_CONNECTION_KEYS = Set.of(
            "type", "url", "username", "password", "path");

    private final Map<String, Map<String, Object>> connections;

    public ConnectionRegistry() {
        this(Map.of());
    }

    public ConnectionRegistry(Map<String, Map<String, Object>> connections) {
        this.connections = new HashMap<>();
        if (connections != null) {
            connections.forEach((name, config) ->
                    this.connections.put(name, new HashMap<>(config)));
        }
    }

    public ReaderConfig resolveReader(Map<String, Object> readerMap) {
        Map<String, Object> source = readerMap == null ? Map.of() : readerMap;
        Map<String, Object> connection = resolveConnectionConfig(source);
        return new ReaderConfig(
                firstNonBlank(
                        asString(source.get("type")),
                        asString(connection.get("type"))),
                connectionName(source),
                asString(source.get("query")),
                firstNonBlank(asString(source.get("path")), asString(connection.get("path"))),
                firstNonBlank(asString(source.get("url")), asString(connection.get("url"))),
                firstNonBlank(asString(source.get("username")), asString(connection.get("username"))),
                firstNonBlank(asString(source.get("password")), asString(connection.get("password"))));
    }

    public ReaderConfig resolveReader(ReaderConfig config) {
        if (config.connection() == null || config.connection().isBlank()) {
            return config;
        }
        Map<String, Object> connection = resolveNamedConnection(config.connection());
        return new ReaderConfig(
                firstNonBlank(config.type(), asString(connection.get("type"))),
                config.connection(),
                config.query(),
                firstNonBlank(config.path(), asString(connection.get("path"))),
                firstNonBlank(config.url(), asString(connection.get("url"))),
                firstNonBlank(config.username(), asString(connection.get("username"))),
                firstNonBlank(config.password(), asString(connection.get("password"))));
    }

    public WriterConfig resolveWriter(Map<String, Object> writerMap) {
        Map<String, Object> source = writerMap == null ? Map.of() : writerMap;
        Map<String, Object> connection = resolveConnectionConfig(source);
        return new WriterConfig(
                firstNonBlank(
                        asString(source.get("type")),
                        asString(connection.get("type"))),
                connectionName(source),
                asString(source.get("mode")),
                asString(source.get("table")),
                firstNonBlank(asString(source.get("path")), asString(connection.get("path"))),
                firstNonBlank(asString(source.get("url")), asString(connection.get("url"))),
                firstNonBlank(asString(source.get("username")), asString(connection.get("username"))),
                firstNonBlank(asString(source.get("password")), asString(connection.get("password"))));
    }

    public WriterConfig resolveWriter(WriterConfig config) {
        if (config.connection() == null || config.connection().isBlank()) {
            return config;
        }
        Map<String, Object> connection = resolveNamedConnection(config.connection());
        return new WriterConfig(
                firstNonBlank(config.type(), asString(connection.get("type"))),
                config.connection(),
                config.mode(),
                config.table(),
                firstNonBlank(config.path(), asString(connection.get("path"))),
                firstNonBlank(config.url(), asString(connection.get("url"))),
                firstNonBlank(config.username(), asString(connection.get("username"))),
                firstNonBlank(config.password(), asString(connection.get("password"))));
    }

    /**
     * 合并命名连接、内联 connection 对象与 reader/writer 上的连接字段。
     * 优先级（高 → 低）：reader/writer 直接字段 &gt; connection 内联对象 &gt; 命名连接注册表。
     */
    private Map<String, Object> resolveConnectionConfig(Map<String, Object> source) {
        Map<String, Object> merged = new HashMap<>();
        Object connectionValue = source.get("connection");
        if (connectionValue instanceof Map<?, ?> inlineMap) {
            inlineMap.forEach((key, value) -> merged.put(String.valueOf(key), value));
        } else if (connectionValue instanceof String connectionName && !connectionName.isBlank()) {
            merged.putAll(resolveNamedConnection(connectionName));
        }
        for (String key : INLINE_CONNECTION_KEYS) {
            String value = asString(source.get(key));
            if (value != null && !value.isBlank()) {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private static String connectionName(Map<String, Object> source) {
        Object connectionValue = source.get("connection");
        if (connectionValue instanceof String connectionName && !connectionName.isBlank()) {
            return connectionName;
        }
        return null;
    }

    private Map<String, Object> resolveNamedConnection(String connectionName) {
        if (connectionName == null || connectionName.isBlank()) {
            return Map.of();
        }
        Map<String, Object> connection = connections.get(connectionName);
        if (connection == null) {
            throw new IllegalArgumentException("Unknown connection: " + connectionName);
        }
        return connection;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
