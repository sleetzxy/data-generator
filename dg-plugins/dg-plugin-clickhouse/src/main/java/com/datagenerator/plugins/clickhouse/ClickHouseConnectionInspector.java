package com.datagenerator.plugins.clickhouse;

import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionInspector;
import com.datagenerator.spi.inspector.ConnectionProbe;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ClickHouse 连接探查：JDBC 连通性与元数据枚举（兼容 catalog/schema 差异）。
 */
public class ClickHouseConnectionInspector implements ConnectionInspector {

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url, String username, String password) throws SQLException;
    }

    private final ConnectionOpener connectionOpener;

    public ClickHouseConnectionInspector() {
        this(DriverManager::getConnection);
    }

    ClickHouseConnectionInspector(ConnectionOpener connectionOpener) {
        this.connectionOpener = connectionOpener;
    }

    @Override
    public String type() {
        return "clickhouse";
    }

    @Override
    public void test(ConnectionProbe probe) throws Exception {
        try (Connection connection = open(probe);
                Statement statement = connection.createStatement();
                ResultSet ignored = statement.executeQuery("SELECT 1")) {
            // 连通即可
        }
    }

    @Override
    public List<String> listTables(ConnectionProbe probe, String schemaOrNull, String patternOrNull)
            throws Exception {
        String schema = blankToNull(schemaOrNull);
        String pattern = blankToNull(patternOrNull);
        if (pattern == null) {
            pattern = "%";
        }
        List<String> tables = new ArrayList<>();
        try (Connection connection = open(probe)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(connection, schema);
            String schemaPattern = schema;
            // CH JDBC 常把 database 放在 TABLE_CAT；schema 参数可能无效
            try (ResultSet resultSet = metaData.getTables(
                    catalog, schemaPattern, pattern, new String[] {"TABLE", "VIEW"})) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (tableName == null || tableName.isBlank()) {
                        continue;
                    }
                    String tableCatalog = resultSet.getString("TABLE_CAT");
                    String tableSchema = resultSet.getString("TABLE_SCHEM");
                    String qualifier = firstNonBlank(tableSchema, tableCatalog);
                    if (qualifier != null
                            && schema == null
                            && !qualifier.equalsIgnoreCase(connection.getCatalog())) {
                        tables.add(qualifier + "." + tableName);
                    } else {
                        tables.add(tableName);
                    }
                }
            }
        }
        return List.copyOf(tables);
    }

    @Override
    public List<ColumnMeta> listColumns(ConnectionProbe probe, String table) throws Exception {
        TableRef ref = TableRef.parse(table);
        Set<String> primaryKeys = new HashSet<>();
        List<ColumnMeta> columns = new ArrayList<>();
        try (Connection connection = open(probe)) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(connection, ref.schema());
            try {
                try (ResultSet pkRs = metaData.getPrimaryKeys(catalog, ref.schema(), ref.table())) {
                    while (pkRs.next()) {
                        String columnName = pkRs.getString("COLUMN_NAME");
                        if (columnName != null) {
                            primaryKeys.add(columnName.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } catch (SQLException ignored) {
                // ClickHouse 部分驱动对 PK 元数据支持不完整
            }
            try (ResultSet colRs = metaData.getColumns(catalog, ref.schema(), ref.table(), "%")) {
                while (colRs.next()) {
                    String name = colRs.getString("COLUMN_NAME");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    String typeName = colRs.getString("TYPE_NAME");
                    if (typeName == null || typeName.isBlank()) {
                        typeName = "unknown";
                    }
                    int nullable = colRs.getInt("NULLABLE");
                    boolean isNullable = nullable != DatabaseMetaData.columnNoNulls;
                    boolean primaryKey = primaryKeys.contains(name.toLowerCase(Locale.ROOT));
                    columns.add(new ColumnMeta(name, typeName, isNullable, primaryKey));
                }
            }
        }
        return List.copyOf(columns);
    }

    private Connection open(ConnectionProbe probe) throws SQLException {
        requireUrl(probe);
        return connectionOpener.open(probe.url(), probe.username(), probe.password());
    }

    private static String resolveCatalog(Connection connection, String schemaOrNull)
            throws SQLException {
        if (schemaOrNull != null && !schemaOrNull.isBlank()) {
            return schemaOrNull;
        }
        return connection.getCatalog();
    }

    private static void requireUrl(ConnectionProbe probe) {
        if (probe == null || probe.url() == null || probe.url().isBlank()) {
            throw new IllegalArgumentException("ClickHouse url must not be blank");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private record TableRef(String schema, String table) {
        static TableRef parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("table must not be blank");
            }
            String trimmed = raw.trim();
            int dot = trimmed.indexOf('.');
            if (dot <= 0 || dot == trimmed.length() - 1) {
                return new TableRef(null, trimmed);
            }
            return new TableRef(trimmed.substring(0, dot), trimmed.substring(dot + 1));
        }
    }
}
