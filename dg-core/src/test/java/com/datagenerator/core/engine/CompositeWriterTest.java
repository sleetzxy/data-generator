package com.datagenerator.core.engine;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeWriterTest {

    @Test
    void write_multipleDelegates_writesToAll() {
        CollectingWriter first = new CollectingWriter();
        CollectingWriter second = new CollectingWriter();
        CompositeWriter writer = new CompositeWriter(List.of(first, second));
        Batch batch = new Batch("orders", List.of(new DataRow(Map.of("id", 1))));

        WriteResult result = writer.write(batch);

        assertThat(result.writtenCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(first.writeCount()).isEqualTo(1);
        assertThat(second.writeCount()).isEqualTo(1);
    }

    @Test
    void write_partialFailure_reportsMinimumWrittenCount() {
        CollectingWriter success = new CollectingWriter();
        CollectingWriter partialFailure = new CollectingWriter(1, 1);
        CompositeWriter writer = new CompositeWriter(List.of(success, partialFailure));
        Batch batch = new Batch("orders", List.of(new DataRow(Map.of("id", 1)), new DataRow(Map.of("id", 2))));

        WriteResult result = writer.write(batch);

        assertThat(result.writtenCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
    }

    @Test
    void constructor_emptyDelegates_throws() {
        assertThatThrownBy(() -> new CompositeWriter(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class CollectingWriter implements DataWriter {

        private final AtomicInteger writes = new AtomicInteger();
        private final int writtenPerBatch;
        private final int failedPerBatch;

        CollectingWriter() {
            this(-1, 0);
        }

        CollectingWriter(int writtenPerBatch, int failedPerBatch) {
            this.writtenPerBatch = writtenPerBatch;
            this.failedPerBatch = failedPerBatch;
        }

        @Override
        public String type() {
            return "mock";
        }

        @Override
        public void init(WriterConfig config) {
        }

        @Override
        public WriteResult write(Batch batch) {
            writes.incrementAndGet();
            if (writtenPerBatch >= 0) {
                return new WriteResult(writtenPerBatch, failedPerBatch);
            }
            return new WriteResult(batch.rows().size(), 0);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        int writeCount() {
            return writes.get();
        }
    }
}
