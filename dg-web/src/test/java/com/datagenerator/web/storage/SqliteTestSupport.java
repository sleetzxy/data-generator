package com.datagenerator.web.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SqliteTestSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SqliteTestSupport() {
    }

    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    public static JdbcTemplate createInMemoryJdbcTemplate() {
        try {
            Path dbFile = Files.createTempFile("dg-test-", ".db");
            dbFile.toFile().deleteOnExit();
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.sqlite.JDBC");
            dataSource.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            SqliteSchemaInitializer.initialize(jdbcTemplate);
            return jdbcTemplate;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create test SQLite database", exception);
        }
    }
}
