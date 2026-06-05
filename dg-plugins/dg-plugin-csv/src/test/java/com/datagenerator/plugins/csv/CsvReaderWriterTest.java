package com.datagenerator.plugins.csv;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReaderWriterTest {

    @Test
    void csvReaderWriter_writeThenRead_returnsSameRows() throws IOException {
        Path file = Files.createTempFile("test", ".csv");
        try {
            WriterConfig writerConfig = new WriterConfig(
                    "csv", null, "insert", null, file.toString(), null, null, null);
            CsvWriter writer = new CsvWriter();
            writer.init(writerConfig);

            List<DataRow> rows = List.of(
                    new DataRow(Map.of("id", "1", "name", "Alice")),
                    new DataRow(Map.of("id", "2", "name", "Bob")),
                    new DataRow(Map.of("id", "3", "name", "Charlie")));

            WriteResult result = writer.write(new Batch(null, rows));
            writer.close();

            assertThat(result.writtenCount()).isEqualTo(3);
            assertThat(result.failedCount()).isZero();

            ReaderConfig readerConfig = new ReaderConfig(
                    "csv", null, null, file.toString(), null, null, null);
            CsvReader reader = new CsvReader();
            reader.init(readerConfig);

            List<DataRow> readRows;
            try (Stream<DataRow> stream = reader.read(new ReadRequest(null))) {
                readRows = stream.toList();
            }
            reader.close();

            assertThat(readRows).hasSize(3);
            assertThat(readRows.stream().map(row -> row.get("id")).toList())
                    .containsExactly("1", "2", "3");
            assertThat(readRows.stream().map(row -> row.get("name")).toList())
                    .containsExactly("Alice", "Bob", "Charlie");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void csvWriter_directoryPath_usesTableNameAsFile() throws IOException {
        Path outputDir = Files.createTempDirectory("csv-output");
        try {
            WriterConfig writerConfig = new WriterConfig(
                    "csv", null, "insert", null, outputDir.toString(), null, null, null);
            CsvWriter writer = new CsvWriter();
            writer.init(writerConfig);

            WriteResult result = writer.write(new Batch(
                    "customers",
                    List.of(new DataRow(Map.of("id", "1", "name", "Alice")))));
            writer.close();

            assertThat(result.writtenCount()).isEqualTo(1);
            Path outputFile = outputDir.resolve("customers.csv");
            assertThat(outputFile).exists();
            assertThat(Files.readString(outputFile)).contains("Alice");
        } finally {
            Files.walk(outputDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // ignore cleanup failures in test
                        }
                    });
        }
    }
}
