package com.datagenerator.plugins.postgresql;

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
class PostgreSqlConnectionInspectorTest {

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
    void type_returnsPostgresql() {
        assertThat(new PostgreSqlConnectionInspector().type()).isEqualTo("postgresql");
    }

    @Test
    void test_blankUrl_throws() {
        PostgreSqlConnectionInspector inspector = new PostgreSqlConnectionInspector();
        assertThatThrownBy(() -> inspector.test(new ConnectionProbe("postgresql", " ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void test_executesSelectOne() throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);

        PostgreSqlConnectionInspector inspector =
                new PostgreSqlConnectionInspector((url, user, pass) -> connection);
        inspector.test(probe());

        verify(statement).executeQuery("SELECT 1");
        verify(connection).close();
    }

    @Test
    void listTables_mapsPublicTablesWithoutSchemaPrefix() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("testdb");
        when(metaData.getTables(eq("testdb"), isNull(), eq("%"), any(String[].class)))
                .thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_SCHEM")).thenReturn("public");
        when(tablesRs.getString("TABLE_NAME")).thenReturn("users");

        PostgreSqlConnectionInspector inspector =
                new PostgreSqlConnectionInspector((url, user, pass) -> connection);
        List<String> tables = inspector.listTables(probe(), null, null);

        assertThat(tables).containsExactly("users");
    }

    @Test
    void listColumns_marksPrimaryKey() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("testdb");
        when(metaData.getPrimaryKeys(eq("testdb"), isNull(), eq("users")))
                .thenReturn(primaryKeysRs);
        when(primaryKeysRs.next()).thenReturn(true, false);
        when(primaryKeysRs.getString("COLUMN_NAME")).thenReturn("id");
        when(metaData.getColumns(eq("testdb"), isNull(), eq("users"), eq("%")))
                .thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, true, false);
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("id", "name");
        when(columnsRs.getString("TYPE_NAME")).thenReturn("int4", "varchar");
        when(columnsRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls, DatabaseMetaData.columnNullable);

        PostgreSqlConnectionInspector inspector =
                new PostgreSqlConnectionInspector((url, user, pass) -> connection);
        List<ColumnMeta> columns = inspector.listColumns(probe(), "users");

        assertThat(columns).containsExactly(
                new ColumnMeta("id", "int4", false, true),
                new ColumnMeta("name", "varchar", true, false));
    }

    @Test
    void listColumns_qualifiedTable_usesSchema() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("testdb");
        when(metaData.getPrimaryKeys(eq("testdb"), eq("app"), eq("orders")))
                .thenReturn(primaryKeysRs);
        when(primaryKeysRs.next()).thenReturn(false);
        when(metaData.getColumns(eq("testdb"), eq("app"), eq("orders"), eq("%")))
                .thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(false);

        PostgreSqlConnectionInspector inspector =
                new PostgreSqlConnectionInspector((url, user, pass) -> connection);
        assertThat(inspector.listColumns(probe(), "app.orders")).isEmpty();

        verify(metaData).getColumns(eq("testdb"), eq("app"), eq("orders"), eq("%"));
    }

    private static ConnectionProbe probe() {
        return new ConnectionProbe("postgresql", "jdbc:postgresql://localhost/db", null, "u", "p");
    }
}
