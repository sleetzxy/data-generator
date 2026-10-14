package com.datagenerator.plugins.csv;

import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionInspector;
import com.datagenerator.spi.inspector.ConnectionProbe;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CSV 连接探查：校验路径可读，并从表头推断列元数据。
 */
public class CsvConnectionInspector implements ConnectionInspector {

    @Override
    public String type() {
        return "csv";
    }

    @Override
    public void test(ConnectionProbe probe) throws Exception {
        resolveReadableFile(probe);
    }

    @Override
    public List<String> listTables(ConnectionProbe probe, String schemaOrNull, String patternOrNull)
            throws Exception {
        Path path = resolveReadableFile(probe);
        String stem = fileStem(path.getFileName().toString());
        if (patternOrNull != null
                && !patternOrNull.isBlank()
                && !matchesSqlLike(stem, patternOrNull)) {
            return List.of();
        }
        return List.of(stem);
    }

    @Override
    public List<ColumnMeta> listColumns(ConnectionProbe probe, String table) throws Exception {
        Path path = resolveReadableFile(probe);
        String stem = fileStem(path.getFileName().toString());
        if (table == null || table.isBlank() || !stem.equalsIgnoreCase(table.trim())) {
            throw new IllegalArgumentException(
                    "CSV table must match file stem '" + stem + "', got: " + table);
        }
        try (Reader fileReader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVReader csvReader = new CSVReaderBuilder(fileReader).build()) {
            String[] headers = csvReader.readNext();
            if (headers == null || headers.length == 0) {
                return List.of();
            }
            List<ColumnMeta> columns = new ArrayList<>(headers.length);
            for (String header : headers) {
                String name = header == null ? "" : header.trim();
                if (name.isEmpty()) {
                    continue;
                }
                columns.add(new ColumnMeta(name, "string", true, false));
            }
            return List.copyOf(columns);
        } catch (CsvValidationException e) {
            throw new IOException("Failed to read CSV header: " + path, e);
        }
    }

    /** 要求 path 存在、为可读普通文件（与 test/listTables/listColumns 共用）。 */
    private static Path resolveReadableFile(ConnectionProbe probe) {
        if (probe == null || probe.path() == null || probe.path().isBlank()) {
            throw new IllegalArgumentException("CSV path must not be blank");
        }
        Path path = Path.of(probe.path()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("CSV path does not exist: " + path);
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("CSV path is not a readable file: " + path);
        }
        return path;
    }

    private static String fileStem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    /** 简易 SQL LIKE：% → 任意串，_ → 单字符，大小写不敏感。 */
    private static boolean matchesSqlLike(String value, String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            switch (ch) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
                .matcher(value)
                .matches();
    }
}
