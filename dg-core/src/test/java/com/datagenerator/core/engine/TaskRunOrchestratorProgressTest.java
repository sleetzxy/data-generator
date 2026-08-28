package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
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

class TaskRunOrchestratorProgressTest {

    private TaskRunOrchestrator orchestrator;
    private AtomicInteger batchCallbacks;

    @BeforeEach
    void setUp() {
        YamlConfigLoader configLoader =
                new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
        ConstraintLoader constraintLoader = new ConstraintLoader(configLoader);
        PluginRegistry pluginRegistry = new PluginRegistry();
        batchCallbacks = new AtomicInteger();
        pluginRegistry.registerWriter("mock", new BatchCountingWriter(batchCallbacks));
        orchestrator = new TaskRunOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(pluginRegistry),
                pluginRegistry,
                new ConnectionRegistry());
    }

    @Test
    void run_withListener_notifiesBatchWrites() {
        var taskConfig = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()))
                .loadTaskConfig("fixtures/task-configs/multi_table.yaml");
        AtomicInteger batchEvents = new AtomicInteger();

        TaskRunResult result = orchestrator.run(
                taskConfig,
                Map.of("type", "mock", "mode", "insert"),
                new GenerationOptions(5, 3, "reject"),
                new TaskRunExecutionListener() {
                    @Override
                    public void onBatchWritten(
                            String tableName,
                            int batchWritten,
                            int batchFailed,
                            long tableWrittenRows,
                            long tableFailedRows,
                            long runWrittenRows,
                            long runFailedRows) {
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
