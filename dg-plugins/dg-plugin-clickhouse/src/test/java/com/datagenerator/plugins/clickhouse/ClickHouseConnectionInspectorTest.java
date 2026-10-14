package com.datagenerator.plugins.clickhouse;

import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClickHouseConnectionInspectorTest {

    @Mock
    private Connection connection;
    @Mock
    private Statement statement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private ResultSet tablesRs;
    @Mock
    private ResultSet columnsRs;
    @Mock
    private ResultSet primaryKeysRs;

    @Test
    void type_returnsClickhouse() {
        assertThat(new ClickHouseConnectionInspector().type()).isEqualTo("clickhouse");
    }

    @Test
    void test_blankUrl_throws() {
        ClickHouseConnectionInspector inspector = new ClickHouseConnectionInspector();
        assertThatThrownBy(() -> inspector.test(new ConnectionProbe("clickhouse", "", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void test_executesSelectOne() throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);

        ClickHouseConnectionInspector inspector =
                new ClickHouseConnectionInspector((url, user, pass) -> connection);
        inspector.test(probe());

        verify(statement).executeQuery("SELECT 1");
        verify(connection).close();
    }

    @Test
    void listTables_usesCatalogFromSchemaHint() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(eq("analytics"), eq("analytics"), eq("%"), any(String[].class)))
                .thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_NAME")).thenReturn("events");
        when(tablesRs.getString("TABLE_CAT")).thenReturn("analytics");
        when(tablesRs.getString("TABLE_SCHEM")).thenReturn(null);

        ClickHouseConnectionInspector inspector =
                new ClickHouseConnectionInspector((url, user, pass) -> connection);
        List<String> tables = inspector.listTables(probe(), "analytics", null);

        assertThat(tables).containsExactly("events");
    }

    @Test
    void listColumns_marksPrimaryKeyWhenAvailable() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("default");
        when(metaData.getPrimaryKeys(eq("default"), isNull(), eq("events")))
                .thenReturn(primaryKeysRs);
        when(primaryKeysRs.next()).thenReturn(true, false);
        when(primaryKeysRs.getString("COLUMN_NAME")).thenReturn("event_id");
        when(metaData.getColumns(eq("default"), isNull(), eq("events"), eq("%")))
                .thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, true, false);
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("event_id", "payload");
        when(columnsRs.getString("TYPE_NAME")).thenReturn("UInt64", "String");
        when(columnsRs.getInt("NULLABLE"))
                .thenReturn(DatabaseMetaData.columnNoNulls, DatabaseMetaData.columnNullable);

        ClickHouseConnectionInspector inspector =
                new ClickHouseConnectionInspector((url, user, pass) -> connection);
        List<ColumnMeta> columns = inspector.listColumns(probe(), "events");

        assertThat(columns).containsExactly(
                new ColumnMeta("event_id", "UInt64", false, true),
                new ColumnMeta("payload", "String", true, false));
    }

    private static ConnectionProbe probe() {
        return new ConnectionProbe("clickhouse", "jdbc:clickhouse://localhost:8123/default", null, "u", "p");
    }
}
