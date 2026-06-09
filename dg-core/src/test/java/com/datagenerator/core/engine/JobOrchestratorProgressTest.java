package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JobOrchestratorProgressTest {

    private JobOrchestrator orchestrator;
    private AtomicInteger batchCallbacks;

    @BeforeEach
    void setUp() {
        YamlConfigLoader configLoader =
                new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
        ConstraintLoader constraintLoader = new ConstraintLoader(configLoader);
        PluginRegistry pluginRegistry = new PluginRegistry();
        batchCallbacks = new AtomicInteger();
        pluginRegistry.registerWriter("mock", new BatchCountingWriter(batchCallbacks));
        orchestrator = new JobOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(pluginRegistry),
                pluginRegistry,
                new ConnectionRegistry());
    }

    @Test
    void run_withListener_notifiesBatchWrites() {
        var job = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()))
                .loadJob("fixtures/jobs/multi_table.yaml");
        AtomicInteger batchEvents = new AtomicInteger();

        JobResult result = orchestrator.run(
                job,
                Map.of("type", "mock", "mode", "insert"),
                new GenerationOptions(5, 3, "reject"),
                new JobExecutionListener() {
                    @Override
                    public void onBatchWritten(
                            String tableName,
                            int batchWritten,
                            int batchFailed,
                            long tableWrittenRows,
                            long tableFailedRows,
                            long jobWrittenRows,
                            long jobFailedRows) {
                        batchEvents.incrementAndGet();
                    }
                });

        assertThat(result.writtenRows()).isEqualTo(15);
        assertThat(batchEvents.get()).isGreaterThan(1);
        assertThat(batchCallbacks.get()).isEqualTo(batchEvents.get());
    }

    static final class BatchCountingWriter implements DataWriter {

        private final AtomicInteger batchCallbacks;

        BatchCountingWriter(AtomicInteger batchCallbacks) {
            this.batchCallbacks = batchCallbacks;
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
            batchCallbacks.incrementAndGet();
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
