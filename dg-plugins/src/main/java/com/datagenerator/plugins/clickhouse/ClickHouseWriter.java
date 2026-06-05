package com.datagenerator.plugins.clickhouse;

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
 * Writes rows to ClickHouse via JDBC batch insert.
 */
public class ClickHouseWriter implements DataWriter {

    private WriterConfig config;
    private Connection connection;

    @Override
    public String type() {
        return "clickhouse";
    }

    @Override
    public void init(WriterConfig config) {
        this.config = config;
        try {
            this.connection = DriverManager.getConnection(
                    config.url(), config.username(), config.password());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open ClickHouse connection", e);
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
                    statement.setObject(index + 1, row.get(columns.get(index)));
                }
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            return new WriteResult(countSuccessful(results), countFailed(results));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write batch to " + tableName, e);
        }
    }

    @Override
    public void flush() {
        // ClickHouse JDBC does not require explicit flush for insert batches.
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close ClickHouse connection", e);
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
}
