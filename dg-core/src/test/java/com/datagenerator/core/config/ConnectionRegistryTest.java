package com.datagenerator.core.config;

import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriterConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionRegistryTest {

    @Test
    void resolveReader_inlineConnectionFields_withoutNamedConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();

        ReaderConfig resolved = registry.resolveReader(Map.of(
                "type", "postgresql",
                "url", "jdbc:postgresql://localhost:5432/demo",
                "username", "demo",
                "password", "secret",
                "query", "SELECT 1"));

        assertThat(resolved.connection()).isNull();
        assertThat(resolved.url()).isEqualTo("jdbc:postgresql://localhost:5432/demo");
        assertThat(resolved.username()).isEqualTo("demo");
        assertThat(resolved.password()).isEqualTo("secret");
        assertThat(resolved.query()).isEqualTo("SELECT 1");
    }

    @Test
    void resolveReader_inlineConnectionMap_withoutNamedConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();

        ReaderConfig resolved = registry.resolveReader(Map.of(
                "type", "postgresql",
                "connection", Map.of(
                        "url", "jdbc:postgresql://localhost:5432/demo",
                        "username", "demo",
                        "password", "secret"),
                "query", "SELECT 1"));

        assertThat(resolved.connection()).isNull();
        assertThat(resolved.url()).isEqualTo("jdbc:postgresql://localhost:5432/demo");
        assertThat(resolved.username()).isEqualTo("demo");
        assertThat(resolved.password()).isEqualTo("secret");
    }

    @Test
    void resolveReader_namedConnection_overriddenByInlineFields() {
        ConnectionRegistry registry = new ConnectionRegistry(Map.of(
                "dev-pg", Map.of(
                        "type", "postgresql",
                        "url", "jdbc:postgresql://registry:5432/db",
                        "username", "registry",
                        "password", "registry-pass")));

        ReaderConfig resolved = registry.resolveReader(Map.of(
                "type", "postgresql",
                "connection", "dev-pg",
                "url", "jdbc:postgresql://override:5432/db",
                "username", "override",
                "query", "SELECT 1"));

        assertThat(resolved.connection()).isEqualTo("dev-pg");
        assertThat(resolved.url()).isEqualTo("jdbc:postgresql://override:5432/db");
        assertThat(resolved.username()).isEqualTo("override");
        assertThat(resolved.password()).isEqualTo("registry-pass");
    }

    @Test
    void resolveWriter_inlineConnectionFields_withoutNamedConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();

        WriterConfig resolved = registry.resolveWriter(Map.of(
                "type", "postgresql",
                "url", "jdbc:postgresql://localhost:5432/demo",
                "username", "demo",
                "password", "secret",
                "mode", "insert"));

        assertThat(resolved.connection()).isNull();
        assertThat(resolved.url()).isEqualTo("jdbc:postgresql://localhost:5432/demo");
        assertThat(resolved.username()).isEqualTo("demo");
        assertThat(resolved.password()).isEqualTo("secret");
    }

    @Test
    void resolveWriter_inlineConnectionMap_withoutNamedConnection() {
        ConnectionRegistry registry = new ConnectionRegistry();

        WriterConfig resolved = registry.resolveWriter(Map.of(
                "type", "clickhouse",
                "connection", Map.of(
                        "url", "jdbc:clickhouse://localhost:8123/default",
                        "username", "default",
                        "password", ""),
                "mode", "insert"));

        assertThat(resolved.connection()).isNull();
        assertThat(resolved.url()).isEqualTo("jdbc:clickhouse://localhost:8123/default");
        assertThat(resolved.username()).isEqualTo("default");
    }

    @Test
    void resolveWriter_unknownNamedConnection_throws() {
        ConnectionRegistry registry = new ConnectionRegistry();

        assertThatThrownBy(() -> registry.resolveWriter(Map.of(
                        "type", "postgresql",
                        "connection", "missing",
                        "mode", "insert")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown connection: missing");
    }

    @Test
    void resolveWriter_connectionPathUsedWhenWriterPathMissing() {
        ConnectionRegistry registry = new ConnectionRegistry(Map.of(
                "local-csv", Map.of("type", "csv", "path", "./output")));

        WriterConfig resolved = registry.resolveWriter(Map.of(
                "type", "csv",
                "connection", "local-csv",
                "mode", "insert"));

        assertThat(resolved.path()).isEqualTo("./output");
    }

    @Test
    void resolveWriter_writerPathOverridesConnectionPath() {
        ConnectionRegistry registry = new ConnectionRegistry(Map.of(
                "local-csv", Map.of("type", "csv", "path", "./output")));

        WriterConfig resolved = registry.resolveWriter(Map.of(
                "type", "csv",
                "connection", "local-csv",
                "mode", "insert",
                "path", "./custom/data.csv"));

        assertThat(resolved.path()).isEqualTo("./custom/data.csv");
    }
}
