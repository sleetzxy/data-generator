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
    private final BatchWrittenCallback batchWrittenCallback;
    private final CancellationChecker cancellationChecker;
    private final List<DataRow> buffer = new ArrayList<>();
    private int writtenRows;
    private int failedRows;

    public GenerationPipeline(DataWriter writer) {
        this(writer, DEFAULT_BATCH_SIZE, BatchWrittenCallback.NOOP, CancellationChecker.NEVER);
    }

    public GenerationPipeline(DataWriter writer, int batchSize) {
        this(writer, batchSize, BatchWrittenCallback.NOOP, CancellationChecker.NEVER);
    }

    public GenerationPipeline(DataWriter writer, int batchSize, BatchWrittenCallback batchWrittenCallback) {
        this(writer, batchSize, batchWrittenCallback, CancellationChecker.NEVER);
    }

    public GenerationPipeline(
            DataWriter writer,
            int batchSize,
            BatchWrittenCallback batchWrittenCallback,
            CancellationChecker cancellationChecker) {
        this.writer = writer;
        this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        this.batchWrittenCallback = batchWrittenCallback == null ? BatchWrittenCallback.NOOP : batchWrittenCallback;
        this.cancellationChecker = cancellationChecker == null ? CancellationChecker.NEVER : cancellationChecker;
    }

    public void accept(String tableName, DataRow row) {
        CancellationChecks.throwIfCancelled(cancellationChecker);
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
        CancellationChecks.throwIfCancelled(cancellationChecker);
        WriteResult result = writer.write(new Batch(tableName, List.copyOf(buffer)));
        writtenRows += result.writtenCount();
        failedRows += result.failedCount();
        buffer.clear();
        batchWrittenCallback.onBatchWritten(
                tableName, result.writtenCount(), result.failedCount(), writtenRows, failedRows);
    }
}
