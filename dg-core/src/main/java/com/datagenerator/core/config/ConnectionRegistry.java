package com.datagenerator.core.config;

import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriterConfig;

import java.util.HashMap;
import java.util.Map;

public class ConnectionRegistry {

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
        String connectionName = asString(source.get("connection"));
        Map<String, Object> connection = resolveConnection(connectionName);
        return new ReaderConfig(
                firstNonBlank(
                        asString(source.get("type")),
                        asString(connection.get("type"))),
                connectionName,
                asString(source.get("query")),
                asString(source.get("path")),
                asString(connection.get("url")),
                asString(connection.get("username")),
                asString(connection.get("password")));
    }

    public ReaderConfig resolveReader(ReaderConfig config) {
        if (config.connection() == null || config.connection().isBlank()) {
            return config;
        }
        Map<String, Object> connection = resolveConnection(config.connection());
        return new ReaderConfig(
                firstNonBlank(config.type(), asString(connection.get("type"))),
                config.connection(),
                config.query(),
                config.path(),
                asString(connection.get("url")),
                asString(connection.get("username")),
                asString(connection.get("password")));
    }

    public WriterConfig resolveWriter(Map<String, Object> writerMap) {
        Map<String, Object> source = writerMap == null ? Map.of() : writerMap;
        String connectionName = asString(source.get("connection"));
        Map<String, Object> connection = resolveConnection(connectionName);
        return new WriterConfig(
                firstNonBlank(
                        asString(source.get("type")),
                        asString(connection.get("type"))),
                connectionName,
                asString(source.get("mode")),
                asString(source.get("table")),
                asString(source.get("path")),
                asString(connection.get("url")),
                asString(connection.get("username")),
                asString(connection.get("password")));
    }

    public WriterConfig resolveWriter(WriterConfig config) {
        if (config.connection() == null || config.connection().isBlank()) {
            return config;
        }
        Map<String, Object> connection = resolveConnection(config.connection());
        return new WriterConfig(
                firstNonBlank(config.type(), asString(connection.get("type"))),
                config.connection(),
                config.mode(),
                config.table(),
                config.path(),
                asString(connection.get("url")),
                asString(connection.get("username")),
                asString(connection.get("password")));
    }

    private Map<String, Object> resolveConnection(String connectionName) {
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
