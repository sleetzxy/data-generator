package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskRunSubmitResult;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.engine.TaskRunResult;
import com.datagenerator.core.engine.TableResult;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.TableTask;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRunServiceAsyncTest {

    @Test
    void submit_largeJob_returnsAsyncAccepted() {
        TaskRunService taskRunService = createTaskRunService(mockOrchestratorReturningSuccess());

        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath("jobs/large.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        TaskRunSubmitResult result = taskRunService.submit(request);
        assertThat(result.async()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(TaskRunStatus.PENDING);

        awaitCompletion(() -> taskRunService.getById(result.response().getRunId()).getStatus() == TaskRunStatus.COMPLETED);
        assertThat(taskRunService.getById(result.response().getRunId()).getStatus()).isEqualTo(TaskRunStatus.COMPLETED);
    }

    @Test
    void cancel_runningJob_staysCancelledNotCompleted() {
        TaskRunOrchestrator orchestrator = mock(TaskRunOrchestrator.class);
        when(orchestrator.run(any(), anyList(), any(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new TaskRunResult(10_000, 10_000, 0, List.of(new TableResult("customers", 10_000, 0, "ok")));
        });

        TaskRunService taskRunService = createTaskRunService(orchestrator);

        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath("jobs/large.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        TaskRunSubmitResult result = taskRunService.submit(request);
        String runId = result.response().getRunId();
        awaitStatus(taskRunService, runId, TaskRunStatus.RUNNING);
        taskRunService.cancel(runId);
        awaitCompletion(() -> {
            TaskRunStatus status = taskRunService.getById(runId).getStatus();
            return status == TaskRunStatus.CANCELLED || status == TaskRunStatus.COMPLETED;
        });

        assertThat(taskRunService.getById(runId).getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
    }

    private static void awaitStatus(TaskRunService taskRunService, String runId, TaskRunStatus expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (taskRunService.getById(runId).getStatus() == expected) {
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
        TaskRunOrchestrator orchestrator = mock(TaskRunOrchestrator.class);
        when(orchestrator.run(any(), anyList(), any(), any())).thenAnswer(invocation -> {
            running.countDown();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new TaskRunResult(100, 100, 0, List.of(new TableResult("customers", 100, 0, "ok")));
        });

        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        TaskConfig taskConfig = new TaskConfig();
        TableTask table = new TableTask();
        table.setName("customers");
        table.setCount(100);
        taskConfig.setTables(List.of(table));
        when(configLoader.loadTaskConfig("jobs/small.yaml")).thenReturn(taskConfig);

        ConnectionRegistry connectionRegistry = new ConnectionRegistry();

        TaskRunRuntimeSettings runtimeSettings = new TaskRunRuntimeSettings(10_000, 1000, 2);
        TaskRunServiceTestSupport.TaskRunServiceContext context = TaskRunServiceTestSupport.createContext(runtimeSettings);
        TaskRunService taskRunService = new TaskRunService(
                orchestrator,
                mock(PreviewOrchestratorFactory.class),
                configLoader,
                mock(ConstraintLoader.class),
                connectionRegistry,
                runtimeSettings,
                context.taskRunRepository(),
                context.taskRunLogRepository(),
                context.asyncTaskRunExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
        TaskRunServiceTestSupport.wireEnqueueToDoSubmit(taskRunService, context.scheduleExecutor());

        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath("jobs/small.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        Thread submitThread = new Thread(() -> {
            try {
                taskRunService.submit(request);
            } catch (RuntimeException ignored) {
                // 取消后可能抛出中断相关异常
            }
        });
        submitThread.start();

        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        String runId = context.taskRunRepository().listAll().stream()
                .filter(TaskRunResponse -> TaskRunResponse.getStatus() == TaskRunStatus.RUNNING)
                .map(com.datagenerator.web.dto.TaskRunResponse::getRunId)
                .findFirst()
                .orElseThrow();

        taskRunService.cancel(runId);
        submitThread.join(10_000);

        assertThat(context.taskRunRepository().findById(runId).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.CANCELLED);
    }

    @Test
    void cancel_completedJob_isNoOp() {
        TaskRunService taskRunService = createTaskRunService(mockOrchestratorReturningSuccess());

        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath("jobs/large.yaml");
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));

        TaskRunSubmitResult result = taskRunService.submit(request);
        awaitCompletion(() -> taskRunService.getById(result.response().getRunId()).getStatus() == TaskRunStatus.COMPLETED);

        org.assertj.core.api.Assertions.assertThatCode(
                () -> taskRunService.cancel(result.response().getRunId()))
                .doesNotThrowAnyException();
        assertThat(taskRunService.getById(result.response().getRunId()).getStatus()).isEqualTo(TaskRunStatus.COMPLETED);
    }

    private static TaskRunService createTaskRunService(TaskRunOrchestrator orchestrator) {
        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        ConstraintLoader constraintLoader = mock(ConstraintLoader.class);
        ConnectionRegistry connectionRegistry = new ConnectionRegistry();

        TaskConfig taskConfig = new TaskConfig();
        TableTask table = new TableTask();
        table.setName("customers");
        table.setCount(10_000);
        taskConfig.setTables(List.of(table));
        when(configLoader.loadTaskConfig("jobs/large.yaml")).thenReturn(taskConfig);

        TaskRunRuntimeSettings runtimeSettings = new TaskRunRuntimeSettings(100, 1000, 2);
        return TaskRunServiceTestSupport.createTaskRunService(runtimeSettings, orchestrator, configLoader);
    }

    private static TaskRunOrchestrator mockOrchestratorReturningSuccess() {
        TaskRunOrchestrator orchestrator = mock(TaskRunOrchestrator.class);
        when(orchestrator.run(any(), anyList(), any(), any()))
                .thenReturn(new TaskRunResult(10_000, 10_000, 0, List.of(new TableResult("customers", 10_000, 0, "ok"))));
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
