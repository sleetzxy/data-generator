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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobServiceAsyncTest {

    @Test
    void submit_largeJob_returnsAsyncAccepted() {
        JobService jobService = createJobService(mockOrchestratorReturningSuccess());

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
    void cancel_runningJob_staysCancelledNotCompleted() {
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        when(orchestrator.run(any(), any(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new JobResult(10_000, 10_000, 0, List.of(new TableResult("customers", 10_000, 0, "ok")));
        });

        JobService jobService = createJobService(orchestrator);

        JobSubmitRequest request = new JobSubmitRequest();
        request.setJobConfig("jobs/large.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        JobSubmitResult result = jobService.submit(request);
        String jobId = result.response().getJobId();
        awaitStatus(jobService, jobId, JobStatus.RUNNING);
        jobService.cancel(jobId);
        awaitCompletion(() -> {
            JobStatus status = jobService.getById(jobId).getStatus();
            return status == JobStatus.CANCELLED || status == JobStatus.COMPLETED;
        });

        assertThat(jobService.getById(jobId).getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    private static void awaitStatus(JobService jobService, String jobId, JobStatus expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (jobService.getById(jobId).getStatus() == expected) {
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

    @Test
    void cancel_syncRunningJob_staysCancelled() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        when(orchestrator.run(any(), any(), any())).thenAnswer(invocation -> {
            running.countDown();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new JobResult(100, 100, 0, List.of(new TableResult("customers", 100, 0, "ok")));
        });

        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        JobDefinition job = new JobDefinition();
        TableTask table = new TableTask();
        table.setName("customers");
        table.setCount(100);
        job.setTables(List.of(table));
        when(configLoader.loadJob("jobs/small.yaml")).thenReturn(job);

        JobRuntimeSettings runtimeSettings = new JobRuntimeSettings(10_000, 1000, 2);
        JobServiceTestSupport.JobServiceContext context = JobServiceTestSupport.createContext(runtimeSettings);
        JobService jobService = new JobService(
                orchestrator,
                mock(PreviewJobOrchestratorFactory.class),
                configLoader,
                mock(ConstraintLoader.class),
                mock(ConnectionRegistry.class),
                runtimeSettings,
                context.jobRepository(),
                context.jobLogStore(),
                context.asyncJobExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
        JobServiceTestSupport.wireEnqueueToDoSubmit(jobService, context.scheduleExecutor());

        JobSubmitRequest request = new JobSubmitRequest();
        request.setJobConfig("jobs/small.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        Thread submitThread = new Thread(() -> {
            try {
                jobService.submit(request);
            } catch (RuntimeException ignored) {
                // 取消后可能抛出中断相关异常
            }
        });
        submitThread.start();

        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        String jobId = context.jobRepository().listAll().stream()
                .filter(jobResponse -> jobResponse.getStatus() == JobStatus.RUNNING)
                .map(com.datagenerator.web.dto.JobResponse::getJobId)
                .findFirst()
                .orElseThrow();

        jobService.cancel(jobId);
        submitThread.join(10_000);

        assertThat(context.jobRepository().findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void cancel_completedJob_isNoOp() {
        JobService jobService = createJobService(mockOrchestratorReturningSuccess());

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

    private static JobService createJobService(JobOrchestrator orchestrator) {
        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        ConstraintLoader constraintLoader = mock(ConstraintLoader.class);
        ConnectionRegistry connectionRegistry = mock(ConnectionRegistry.class);

        JobDefinition job = new JobDefinition();
        TableTask table = new TableTask();
        table.setName("customers");
        table.setCount(10_000);
        job.setTables(List.of(table));
        when(configLoader.loadJob("jobs/large.yaml")).thenReturn(job);

        JobRuntimeSettings runtimeSettings = new JobRuntimeSettings(100, 1000, 2);
        return JobServiceTestSupport.createJobService(runtimeSettings, orchestrator, configLoader);
    }

    private static JobOrchestrator mockOrchestratorReturningSuccess() {
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        when(orchestrator.run(any(), any(), any()))
                .thenReturn(new JobResult(10_000, 10_000, 0, List.of(new TableResult("customers", 10_000, 0, "ok"))));
        return orchestrator;
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
