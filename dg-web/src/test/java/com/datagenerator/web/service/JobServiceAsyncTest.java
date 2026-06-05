package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.JobSubmitRequest;
import com.datagenerator.web.dto.JobSubmitResult;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.JobResult;
import com.datagenerator.core.engine.TableResult;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobServiceAsyncTest {

    @Test
    void submit_largeJob_returnsAsyncAccepted() {
        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        ConstraintLoader constraintLoader = mock(ConstraintLoader.class);
        ConnectionRegistry connectionRegistry = mock(ConnectionRegistry.class);

        JobDefinition job = new JobDefinition();
        TableTask table = new TableTask();
        table.setName("customers");
        table.setCount(10_000);
        job.setTables(List.of(table));
        when(configLoader.loadJob("jobs/large.yaml")).thenReturn(job);
        when(orchestrator.run(any(), any(), any()))
                .thenReturn(new JobResult(10_000, 10_000, 0, List.of(new TableResult("customers", 10_000, 0, "ok"))));

        JobService jobService = new JobService(
                orchestrator,
                mock(PreviewJobOrchestratorFactory.class),
                configLoader,
                constraintLoader,
                connectionRegistry,
                new JobRuntimeSettings(100, 1000, 2),
                new JobLogStore());

        JobSubmitRequest request = new JobSubmitRequest();
        request.setJobConfig("jobs/large.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        JobSubmitResult result = jobService.submit(request);
        assertThat(result.async()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(JobStatus.PENDING);

        awaitCompletion(() -> jobService.getById(result.response().getJobId()).getStatus() == JobStatus.COMPLETED);
        assertThat(jobService.getById(result.response().getJobId()).getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void cancel_completedJob_isNoOp() {
        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        ConstraintLoader constraintLoader = mock(ConstraintLoader.class);
        ConnectionRegistry connectionRegistry = mock(ConnectionRegistry.class);

        JobDefinition job = new JobDefinition();
        TableTask table = new TableTask();
        table.setName("customers");
        table.setCount(10_000);
        job.setTables(List.of(table));
        when(configLoader.loadJob("jobs/large.yaml")).thenReturn(job);
        when(orchestrator.run(any(), any(), any()))
                .thenReturn(new JobResult(10_000, 10_000, 0, List.of(new TableResult("customers", 10_000, 0, "ok"))));

        JobService jobService = new JobService(
                orchestrator,
                mock(PreviewJobOrchestratorFactory.class),
                configLoader,
                constraintLoader,
                connectionRegistry,
                new JobRuntimeSettings(100, 1000, 2),
                new JobLogStore());

        JobSubmitRequest request = new JobSubmitRequest();
        request.setJobConfig("jobs/large.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        JobSubmitResult result = jobService.submit(request);
        awaitCompletion(() -> jobService.getById(result.response().getJobId()).getStatus() == JobStatus.COMPLETED);

        org.assertj.core.api.Assertions.assertThatCode(
                () -> jobService.cancel(result.response().getJobId()))
                .doesNotThrowAnyException();
        assertThat(jobService.getById(result.response().getJobId()).getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    private static void awaitCompletion(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
