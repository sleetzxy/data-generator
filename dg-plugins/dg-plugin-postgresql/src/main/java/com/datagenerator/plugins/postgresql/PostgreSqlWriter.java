package com.datagenerator.plugins.postgresql;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes rows to PostgreSQL via JDBC batch insert.
 */
public class PostgreSqlWriter implements DataWriter {

    private WriterConfig config;
    private Connection connection;

    @Override
    public String type() {
        return "postgresql";
    }

    @Override
    public void init(WriterConfig config) {
        this.config = config;
        try {
            this.connection = DriverManager.getConnection(
                    config.url(), config.username(), config.password());
            this.connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open PostgreSQL connection", e);
        }
    }

    @Override
    public WriteResult write(Batch batch) {
        if (!"insert".equalsIgnoreCase(config.mode())) {
            throw new UnsupportedOperationException("Only insert mode is supported, got: " + config.mode());
        }
        List<DataRow> rows = batch.rows();
        if (rows.isEmpty()) {
            return new WriteResult(0, 0);
        }

        String tableName = batch.tableName() != null ? batch.tableName() : config.table();
        List<String> columns = new ArrayList<>(rows.getFirst().getFields().keySet());
        String sql = buildInsertSql(tableName, columns);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (DataRow row : rows) {
                for (int index = 0; index < columns.size(); index++) {
                    String columnName = columns.get(index);
                    Object value = PostgreSqlParameterBinder.prepareValue(columnName, row.get(columnName));
                    statement.setObject(index + 1, value);
                }
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            connection.commit();
            return new WriteResult(countSuccessful(results), countFailed(results));
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to write batch to " + tableName, e);
        }
    }

    @Override
    public void flush() {
        try {
            if (connection != null && !connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to flush PostgreSQL connection", e);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close PostgreSQL connection", e);
            } finally {
                connection = null;
            }
        }
    }

    private static String buildInsertSql(String tableName, List<String> columns) {
        String columnList = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        return "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + placeholders + ")";
    }

    private static int countSuccessful(int[] results) {
        int count = 0;
        for (int result : results) {
            if (result >= 0 || result == Statement.SUCCESS_NO_INFO) {
                count++;
            }
        }
        return count;
    }

    private static int countFailed(int[] results) {
        int count = 0;
        for (int result : results) {
            if (result == Statement.EXECUTE_FAILED) {
                count++;
            }
        }
        return count;
    }

    private void rollbackQuietly() {
        try {
            if (connection != null) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // best-effort rollback
        }
    }
}
