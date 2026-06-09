package com.datagenerator.web.internal;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CollectingWriter implements DataWriter {

    public static final String TYPE = "memory";

    private final Map<String, List<DataRow>> rowsByTable = new LinkedHashMap<>();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(WriterConfig config) {
    }

    @Override
    public WriteResult write(Batch batch) {
        rowsByTable.computeIfAbsent(batch.tableName(), ignored -> new ArrayList<>())
                .addAll(batch.rows());
        return new WriteResult(batch.rows().size(), 0);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    public Map<String, List<Map<String, Object>>> toRowMaps() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<DataRow>> entry : rowsByTable.entrySet()) {
            List<Map<String, Object>> rows = entry.getValue().stream()
                    .map(row -> {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        copy.putAll(row.getFields());
                        return copy;
                    })
                    .toList();
            result.put(entry.getKey(), rows);
        }
        return result;
    }
}
