package com.datagenerator.plugins.clickhouse;

import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads rows from ClickHouse via JDBC.
 */
public class ClickHouseReader implements DataReader {

    private final ThreadLocal<ReaderConfig> configHolder = new ThreadLocal<>();

    @Override
    public String type() {
        return "clickhouse";
    }

    @Override
    public void init(ReaderConfig config) {
        configHolder.set(config);
    }

    @Override
    public Stream<DataRow> read(ReadRequest request) {
        ReaderConfig config = configHolder.get();
        if (config == null) {
            throw new IllegalStateException("ClickHouseReader is not initialized");
        }
        String query = resolveQuery(request, config);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query must not be blank");
        }
        try {
            Connection connection = DriverManager.getConnection(
                    config.url(), config.username(), config.password());
            PreparedStatement statement = connection.prepareStatement(query);
            ResultSet resultSet = statement.executeQuery();

            Spliterator<DataRow> spliterator = new Spliterators.AbstractSpliterator<>(
                    Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
                @Override
                public boolean tryAdvance(Consumer<? super DataRow> action) {
                    try {
                        if (resultSet.next()) {
                            action.accept(mapRow(resultSet));
                            return true;
                        }
                        return false;
                    } catch (SQLException e) {
                        throw new RuntimeException("Failed to read row", e);
                    }
                }
            };

            return StreamSupport.stream(spliterator, false)
                    .onClose(() -> closeQuietly(resultSet, statement, connection));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute query", e);
        }
    }

    @Override
    public void close() {
        // Connections are opened per read() and closed when the stream is closed.
    }

    private String resolveQuery(ReadRequest request, ReaderConfig config) {
        if (request != null && request.query() != null && !request.query().isBlank()) {
            return request.query();
        }
        return config.query();
    }

    private static DataRow mapRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        DataRow row = new DataRow();
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            row.set(metaData.getColumnLabel(column), resultSet.getObject(column));
        }
        return row;
    }

    private static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }
}
