package com.datagenerator.core.engine;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.List;

public class GenerationPipeline {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    private final DataWriter writer;
    private final int batchSize;
    private final List<DataRow> buffer = new ArrayList<>();
    private int writtenRows;
    private int failedRows;

    public GenerationPipeline(DataWriter writer) {
        this(writer, DEFAULT_BATCH_SIZE);
    }

    public GenerationPipeline(DataWriter writer, int batchSize) {
        this.writer = writer;
        this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
    }

    public void accept(String tableName, DataRow row) {
        buffer.add(row);
        if (buffer.size() >= batchSize) {
            flushBatch(tableName);
        }
    }

    public void flush(String tableName) {
        if (!buffer.isEmpty()) {
            flushBatch(tableName);
        }
        writer.flush();
    }

    public int getWrittenRows() {
        return writtenRows;
    }

    public int getFailedRows() {
        return failedRows;
    }

    private void flushBatch(String tableName) {
        WriteResult result = writer.write(new Batch(tableName, List.copyOf(buffer)));
        writtenRows += result.writtenCount();
        failedRows += result.failedCount();
        buffer.clear();
    }
}
