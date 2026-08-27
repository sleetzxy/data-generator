package com.datagenerator.web.service;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.storage.TaskRunLogRepository;
import com.datagenerator.web.storage.TaskRunRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.model.YamlConfigLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TaskRunServiceTestSupport {

    private TaskRunServiceTestSupport() {
    }

    public record TaskRunServiceContext(
            TaskRunRepository taskRunRepository,
            TaskRunLogRepository taskRunLogRepository,
            AsyncTaskRunExecutor asyncTaskRunExecutor,
            TaskRunCancellationRegistry cancellationRegistry,
            TaskRunQueueExecutor scheduleExecutor) {}

    public static TaskRunServiceContext createContext(TaskRunRuntimeSettings runtimeSettings) {
        return createContext(runtimeSettings, Path.of(System.getProperty("java.io.tmpdir"), "dg-test-logs"));
    }

    public static TaskRunServiceContext createContext(TaskRunRuntimeSettings runtimeSettings, Path logDir) {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        TaskRunRepository taskRunRepository = new TaskRunRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
        DataGeneratorProperties properties = new DataGeneratorProperties();
        properties.getStorage().setLogDir(logDir.toString());
        TaskRunLogRepository taskRunLogRepository = new TaskRunLogRepository(properties);
        TaskRunCancellationRegistry cancellationRegistry = new TaskRunCancellationRegistry();
        AsyncTaskRunExecutor asyncTaskRunExecutor = new AsyncTaskRunExecutor(
                runtimeSettings, taskRunRepository, taskRunLogRepository, cancellationRegistry);
        TaskRunQueueExecutor scheduleExecutor = mock(TaskRunQueueExecutor.class);
        return new TaskRunServiceContext(
                taskRunRepository, taskRunLogRepository, asyncTaskRunExecutor, cancellationRegistry, scheduleExecutor);
    }

    public static void wireEnqueueToDoSubmit(TaskRunService taskRunService, TaskRunQueueExecutor scheduleExecutor) {
        when(scheduleExecutor.enqueue(any(), any(), any())).thenAnswer(invocation -> taskRunService.doSubmit(
                invocation.getArgument(2),
                invocation.getArgument(1)));
    }

    public static TaskRunService createTaskRunService(
            TaskRunRuntimeSettings runtimeSettings,
            TaskRunOrchestrator orchestrator,
            YamlConfigLoader configLoader) {
        TaskRunServiceContext context = createContext(runtimeSettings);
        TaskRunService taskRunService = new TaskRunService(
                orchestrator,
                mock(PreviewOrchestratorFactory.class),
                configLoader,
                mock(ConstraintLoader.class),
                new ConnectionRegistry(),
                runtimeSettings,
                context.taskRunRepository(),
                context.taskRunLogRepository(),
                context.asyncTaskRunExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
        wireEnqueueToDoSubmit(taskRunService, context.scheduleExecutor());
        return taskRunService;
    }
}
