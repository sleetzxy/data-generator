package com.datagenerator.plugins.postgresql;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgreSqlIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void writer_insertsBatch() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT, name VARCHAR(100))");
        }

        WriterConfig config = writerConfig("users");
        PostgreSqlWriter writer = new PostgreSqlWriter();
        writer.init(config);

        List<DataRow> rows = List.of(
                new DataRow(Map.of("id", 1L, "name", "Alice")),
                new DataRow(Map.of("id", 2L, "name", "Bob")),
                new DataRow(Map.of("id", 3L, "name", "Charlie")));

        WriteResult result = writer.write(new Batch("users", rows));
        writer.close();

        assertThat(result.writtenCount()).isEqualTo(3);
        assertThat(result.failedCount()).isEqualTo(0);

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM users")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    void reader_readsQuery() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE regions (code VARCHAR(10))");
            statement.execute("INSERT INTO regions (code) VALUES ('BJ'), ('SH')");
        }

        ReaderConfig config = new ReaderConfig(
                "postgresql",
                null,
                "SELECT code FROM regions ORDER BY code",
                null,
                pg.getJdbcUrl(),
                pg.getUsername(),
                pg.getPassword());

        PostgreSqlReader reader = new PostgreSqlReader();
        reader.init(config);

        List<DataRow> rows;
        try (Stream<DataRow> stream = reader.read(new ReadRequest(null))) {
            rows = stream.toList();
        }
        reader.close();

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> row.get("code")).toList())
                .containsExactly("BJ", "SH");
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static WriterConfig writerConfig(String table) {
        return new WriterConfig(
                "postgresql",
                null,
                "insert",
                table,
                null,
                pg.getJdbcUrl(),
                pg.getUsername(),
                pg.getPassword());
    }
}
