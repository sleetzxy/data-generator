package com.datagenerator.plugins.csv;

import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.reader.DataReader;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads rows from a CSV file with a header row via OpenCSV.
 */
public class CsvReader implements DataReader {

    private final ThreadLocal<ReaderConfig> configHolder = new ThreadLocal<>();

    @Override
    public String type() {
        return "csv";
    }

    @Override
    public void init(ReaderConfig config) {
        configHolder.set(config);
    }

    @Override
    public Stream<DataRow> read(ReadRequest request) {
        ReaderConfig config = configHolder.get();
        if (config == null) {
            throw new IllegalStateException("CsvReader is not initialized");
        }
        String path = config.path();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        try {
            Reader fileReader = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8);
            CSVReader csvReader = new CSVReaderBuilder(fileReader).build();
            String[] headers = readNextRow(csvReader);
            if (headers == null) {
                closeQuietly(csvReader, fileReader);
                return Stream.empty();
            }

            Spliterator<DataRow> spliterator = new Spliterators.AbstractSpliterator<>(
                    Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
                @Override
                public boolean tryAdvance(Consumer<? super DataRow> action) {
                    try {
                        String[] values = readNextRow(csvReader);
                        if (values == null) {
                            return false;
                        }
                        action.accept(mapRow(headers, values));
                        return true;
                    } catch (CsvValidationException e) {
                        throw new RuntimeException("Failed to read CSV row", e);
                    }
                }
            };

            return StreamSupport.stream(spliterator, false)
                    .onClose(() -> closeQuietly(csvReader, fileReader));
        } catch (IOException | CsvValidationException e) {
            throw new RuntimeException("Failed to open CSV file: " + path, e);
        }
    }

    @Override
    public void close() {
        // Resources are opened per read() and closed when the stream is closed.
    }

    private static String[] readNextRow(CSVReader csvReader) throws CsvValidationException {
        try {
            return csvReader.readNext();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV row", e);
        }
    }

    private static DataRow mapRow(String[] headers, String[] values) {
        DataRow row = new DataRow();
        int columnCount = Math.min(headers.length, values.length);
        for (int index = 0; index < columnCount; index++) {
            row.set(headers[index], values[index]);
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
