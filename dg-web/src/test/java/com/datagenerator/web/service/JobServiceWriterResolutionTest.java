package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobSubmitRequest;
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
                new ConnectionRegistry(),
                runtimeSettings,
                context.jobRepository(),
                context.jobLogStore(),
                context.asyncJobExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
    }

    @Test
    void resolveRuntimeWriters_jobYamlOnly_usesJobWriter() throws Exception {
        JobDefinition job = jobWithTable("orders", Map.of());
        job.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        List<Map<String, Object>> resolved = invokeResolveRuntimeWriters(job, requestWithWriter(Map.of()));

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveRuntimeWriters_jobWritersOverrideRequestWriters() throws Exception {
        JobDefinition job = jobWithTable("orders", Map.of());
        job.setWriters(List.of(
                Map.of("type", "postgresql", "connection", "pg", "mode", "insert"),
                Map.of("type", "clickhouse", "connection", "ck", "mode", "insert")));

        List<Map<String, Object>> resolved = invokeResolveRuntimeWriters(
                job,
                requestWithWriters(List.of(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"))));

        assertThat(resolved).containsExactly(
                Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));
    }

    @Test
    void resolveRuntimeWriters_tableWriterOverridesJobWriter() throws Exception {
        JobDefinition job = jobWithTable(
                "orders",
                Map.of("type", "csv", "connection", "traffic-output", "mode", "insert"));
        job.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        invokeResolveRuntimeWriters(job, requestWithWriter(Map.of()));
    }

    @Test
    void resolveRuntimeWriters_missingTableWriter_throws() {
        JobDefinition job = jobWithTable("orders", Map.of());

        assertThatThrownBy(() -> invokeResolveRuntimeWriters(job, requestWithWriter(Map.of())))
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

    private static JobSubmitRequest requestWithWriter(Map<String, Object> writer) {
        JobSubmitRequest request = new JobSubmitRequest();
        request.setWriter(writer);
        return request;
    }

    private static JobSubmitRequest requestWithWriters(List<Map<String, Object>> writers) {
        JobSubmitRequest request = new JobSubmitRequest();
        request.setWriters(writers);
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeResolveRuntimeWriters(
            JobDefinition job, JobSubmitRequest request) throws Exception {
        var method = JobService.class.getDeclaredMethod(
                "resolveRuntimeWriters", JobDefinition.class, JobSubmitRequest.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(jobService, job, request);
    }
}
