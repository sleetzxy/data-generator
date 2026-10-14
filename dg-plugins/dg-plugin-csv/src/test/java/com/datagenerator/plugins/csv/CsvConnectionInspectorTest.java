package com.datagenerator.plugins.csv;

import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvConnectionInspectorTest {

    private final CsvConnectionInspector inspector = new CsvConnectionInspector();

    @TempDir
    Path tempDir;

    @Test
    void type_returnsCsv() {
        assertThat(inspector.type()).isEqualTo("csv");
    }

    @Test
    void test_readableFile_succeeds() throws Exception {
        Path file = tempDir.resolve("users.csv");
        Files.writeString(file, "id,name\n1,Alice\n");

        inspector.test(probe(file.toString()));
    }

    @Test
    void test_missingPath_throws() {
        assertThatThrownBy(() -> inspector.test(probe(tempDir.resolve("missing.csv").toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void test_blankPath_throws() {
        assertThatThrownBy(() -> inspector.test(probe("  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listTables_returnsFileStem() throws Exception {
        Path file = tempDir.resolve("orders.csv");
        Files.writeString(file, "id\n1\n");

        List<String> tables = inspector.listTables(probe(file.toString()), null, null);

        assertThat(tables).containsExactly("orders");
    }

    @Test
    void listTables_patternFiltersStem() throws Exception {
        Path file = tempDir.resolve("orders.csv");
        Files.writeString(file, "id\n1\n");

        assertThat(inspector.listTables(probe(file.toString()), null, "ord%"))
                .containsExactly("orders");
        assertThat(inspector.listTables(probe(file.toString()), null, "users%"))
                .isEmpty();
    }

    @Test
    void listColumns_readsHeaderAsStringColumns() throws Exception {
        Path file = tempDir.resolve("users.csv");
        Files.writeString(file, "id,name,email\n1,Alice,a@example.com\n");

        List<ColumnMeta> columns = inspector.listColumns(probe(file.toString()), "users");

        assertThat(columns).containsExactly(
                new ColumnMeta("id", "string", true, false),
                new ColumnMeta("name", "string", true, false),
                new ColumnMeta("email", "string", true, false));
    }

    @Test
    void listColumns_tableNameCaseInsensitive_succeeds() throws Exception {
        Path file = tempDir.resolve("Users.csv");
        Files.writeString(file, "id\n1\n");

        assertThat(inspector.listColumns(probe(file.toString()), "users"))
                .containsExactly(new ColumnMeta("id", "string", true, false));
    }

    @Test
    void listColumns_tableMismatch_throws() throws Exception {
        Path file = tempDir.resolve("users.csv");
        Files.writeString(file, "id\n1\n");

        assertThatThrownBy(() -> inspector.listColumns(probe(file.toString()), "orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("users")
                .hasMessageContaining("orders");
    }

    @Test
    void listColumns_blankTable_throws() throws Exception {
        Path file = tempDir.resolve("users.csv");
        Files.writeString(file, "id\n1\n");

        assertThatThrownBy(() -> inspector.listColumns(probe(file.toString()), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
    }

    @Test
    void listColumns_emptyFile_returnsEmptyList() throws Exception {
        Path file = tempDir.resolve("empty.csv");
        Files.writeString(file, "");

        assertThat(inspector.listColumns(probe(file.toString()), "empty")).isEmpty();
    }

    @Test
    void listTables_directoryPath_throws() {
        assertThatThrownBy(() -> inspector.listTables(probe(tempDir.toString()), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readable file");
    }

    @Test
    void listColumns_directoryPath_throws() {
        assertThatThrownBy(() -> inspector.listColumns(probe(tempDir.toString()), "anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readable file");
    }

    @Test
    void test_directoryPath_throws() {
        assertThatThrownBy(() -> inspector.test(probe(tempDir.toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readable file");
    }

    private static ConnectionProbe probe(String path) {
        return new ConnectionProbe("csv", null, path, null, null);
    }
}
