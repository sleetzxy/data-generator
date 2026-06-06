package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class JobServiceWriterResolutionTest {

    private JobService jobService;

    @BeforeEach
    void setUp() {
        JobRuntimeSettings runtimeSettings = new JobRuntimeSettings(5000, 1000, 2);
        JobServiceTestSupport.JobServiceContext context = JobServiceTestSupport.createContext(runtimeSettings);
        jobService = new JobService(
                mock(JobOrchestrator.class),
                mock(PreviewJobOrchestratorFactory.class),
                mock(YamlConfigLoader.class),
                mock(ConstraintLoader.class),
                mock(ConnectionRegistry.class),
                runtimeSettings,
                context.jobRepository(),
                context.jobLogStore(),
                context.asyncJobExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
    }

    @Test
    void resolveWriter_jobYamlOnly_usesJobWriter() throws Exception {
        JobDefinition job = jobWithTable("orders", Map.of());
        job.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        Map<String, Object> resolved = invokeResolveWriter(job, Map.of());

        assertThat(resolved).containsEntry("type", "csv");
        assertThat(resolved).containsEntry("connection", "local-csv");
    }

    @Test
    void resolveWriter_jobOverridesRequestWriter() throws Exception {
        JobDefinition job = jobWithTable("orders", Map.of());
        job.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        Map<String, Object> resolved = invokeResolveWriter(
                job,
                Map.of("connection", "traffic-output"));

        assertThat(resolved).containsEntry("type", "csv");
        assertThat(resolved).containsEntry("connection", "local-csv");
    }

    @Test
    void resolveWriter_tableWriterOverridesJobWriter() throws Exception {
        JobDefinition job = jobWithTable(
                "orders",
                Map.of("type", "csv", "connection", "traffic-output", "mode", "insert"));
        job.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        Map<String, Object> resolved = invokeResolveWriter(job, Map.of());

        assertThat(resolved).containsEntry("connection", "local-csv");
    }

    @Test
    void resolveWriter_missingTableWriter_throws() {
        JobDefinition job = jobWithTable("orders", Map.of());

        assertThatThrownBy(() -> invokeResolveWriter(job, Map.of()))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause()
                .hasMessageContaining("orders");
    }

    private static JobDefinition jobWithTable(String tableName, Map<String, Object> tableWriter) {
        JobDefinition job = new JobDefinition();
        TableTask table = new TableTask();
        table.setName(tableName);
        table.setCount(10);
        table.setWriter(tableWriter);
        job.setTables(List.of(table));
        return job;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeResolveWriter(
            JobDefinition job, Map<String, Object> requestWriter) throws Exception {
        var method = JobService.class.getDeclaredMethod(
                "resolveWriter", JobDefinition.class, Map.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(jobService, job, requestWriter);
    }
}
