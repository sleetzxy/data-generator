package com.datagenerator.core.engine;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationPipelineTest {

    @Test
    void flushBatch_invokesCallbackForEachBatch() {
        AtomicInteger callbackCount = new AtomicInteger();
        List<Integer> batchSizes = new ArrayList<>();
        CountingWriter writer = new CountingWriter();
        GenerationPipeline pipeline = new GenerationPipeline(writer, 3, (table, batchWritten, batchFailed, tableWrittenRows, tableFailedRows) -> {
            callbackCount.incrementAndGet();
            batchSizes.add(batchWritten);
            assertThat(tableWrittenRows).isEqualTo(batchSizes.stream().mapToInt(Integer::intValue).sum());
        });

        for (int i = 0; i < 7; i++) {
            pipeline.accept("items", new DataRow(Map.of("id", i)));
        }
        pipeline.flush("items");

        assertThat(callbackCount.get()).isEqualTo(3);
        assertThat(batchSizes).containsExactly(3, 3, 1);
        assertThat(pipeline.getWrittenRows()).isEqualTo(7);
    }

    static final class CountingWriter implements DataWriter {

        @Override
        public String type() {
            return "mock";
        }

        @Override
        public void init(com.datagenerator.spi.model.WriterConfig config) {
        }

        @Override
        public WriteResult write(Batch batch) {
            return new WriteResult(batch.rows().size(), 0);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
