package com.datagenerator.plugins.csv;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;
import com.opencsv.ICSVWriter;
import com.opencsv.CSVWriterBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes rows to a CSV file with a header row via OpenCSV.
 */
public class CsvWriter implements DataWriter {

    private WriterConfig config;
    private ICSVWriter csvWriter;
    private Writer fileWriter;
    private boolean headerWritten;

    @Override
    public String type() {
        return "csv";
    }

    @Override
    public void init(WriterConfig config) {
        this.config = config;
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

        ensureWriterOpen();
        List<String> columns = new ArrayList<>(rows.getFirst().getFields().keySet());
        if (!headerWritten) {
            csvWriter.writeNext(columns.toArray(new String[0]));
            headerWritten = true;
        }

        for (DataRow row : rows) {
            String[] values = columns.stream()
                    .map(column -> toCsvValue(row.get(column)))
                    .toArray(String[]::new);
            csvWriter.writeNext(values);
        }
        return new WriteResult(rows.size(), 0);
    }

    @Override
    public void flush() {
        if (csvWriter != null) {
            try {
                csvWriter.flush();
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush CSV writer", e);
            }
        }
    }

    @Override
    public void close() {
        if (csvWriter != null) {
            try {
                csvWriter.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to close CSV writer", e);
            } finally {
                csvWriter = null;
                fileWriter = null;
            }
        }
    }

    private void ensureWriterOpen() {
        if (csvWriter != null) {
            return;
        }
        String path = config.path();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        try {
            Path filePath = Path.of(path);
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            fileWriter = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8);
            csvWriter = new CSVWriterBuilder(fileWriter).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open CSV file: " + path, e);
        }
    }

    private static String toCsvValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
