package com.datagenerator.core.config;

import com.datagenerator.spi.model.WriterConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRegistryTest {

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
