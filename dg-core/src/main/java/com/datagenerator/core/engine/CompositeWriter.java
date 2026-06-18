package com.datagenerator.core.engine;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Fan-out writer that writes each batch to multiple underlying writers.
 */
public final class CompositeWriter implements DataWriter {

    private final List<DataWriter> writers;

    public CompositeWriter(List<DataWriter> writers) {
        if (writers == null || writers.isEmpty()) {
            throw new IllegalArgumentException("CompositeWriter requires at least one delegate writer");
        }
        this.writers = List.copyOf(writers);
    }

    @Override
    public String type() {
        return "composite";
    }

    @Override
    public void init(WriterConfig config) {
        throw new UnsupportedOperationException("CompositeWriter delegates are initialized individually");
    }

    @Override
    public WriteResult write(Batch batch) {
        int minWritten = batch.rows().size();
        int maxFailed = 0;
        for (DataWriter writer : writers) {
            WriteResult result = writer.write(batch);
            minWritten = Math.min(minWritten, result.writtenCount());
            maxFailed = Math.max(maxFailed, result.failedCount());
        }
        int failed = Math.max(maxFailed, batch.rows().size() - minWritten);
        return new WriteResult(minWritten, failed);
    }

    @Override
    public void flush() {
        for (DataWriter writer : writers) {
            writer.flush();
        }
    }

    @Override
    public void close() {
        for (DataWriter writer : writers) {
            writer.close();
        }
    }

    List<DataWriter> delegates() {
        return new ArrayList<>(writers);
    }
}
