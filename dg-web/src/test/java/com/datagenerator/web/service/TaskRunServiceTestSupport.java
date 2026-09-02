package com.datagenerator.web.service;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.storage.TaskRepository;
import com.datagenerator.web.storage.TaskRunLogRepository;
import com.datagenerator.web.storage.TaskRunRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.model.YamlConfigLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;

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
            TaskRunQueueExecutor scheduleExecutor,
            TaskRepository taskRepository) {}

    public static TaskRunServiceContext createContext(TaskRunRuntimeSettings runtimeSettings) {
        return createContext(runtimeSettings, Path.of(System.getProperty("java.io.tmpdir"), "dg-test-logs"));
    }

    public static TaskRunServiceContext createContext(TaskRunRuntimeSettings runtimeSettings, Path logDir) {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        TaskRunRepository taskRunRepository = new TaskRunRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
        TaskRepository taskRepository = new TaskRepository(jdbcTemplate);
        // 预插任务主表行：提交/预览要求任务已在主表登记（各测试类用 demo/large/small 配置文件路径）
        insertTaskRecord(taskRepository, "demo");
        insertTaskRecord(taskRepository, "large");
        insertTaskRecord(taskRepository, "small");
        DataGeneratorProperties properties = new DataGeneratorProperties();
        properties.getStorage().setLogDir(logDir.toString());
        TaskRunLogRepository taskRunLogRepository = new TaskRunLogRepository(properties);
        TaskRunCancellationRegistry cancellationRegistry = new TaskRunCancellationRegistry();
        AsyncTaskRunExecutor asyncTaskRunExecutor = new AsyncTaskRunExecutor(
                runtimeSettings, taskRunRepository, taskRunLogRepository, cancellationRegistry);
        TaskRunQueueExecutor scheduleExecutor = mock(TaskRunQueueExecutor.class);
        return new TaskRunServiceContext(
                taskRunRepository, taskRunLogRepository, asyncTaskRunExecutor,
                cancellationRegistry, scheduleExecutor, taskRepository);
    }

    /** 预插单条任务行：id/display_name 与 file_name 同名，调度关闭 */
    private static void insertTaskRecord(TaskRepository taskRepository, String fileName) {
        taskRepository.insert(new TaskRepository.TaskRecord(
                fileName, fileName, fileName, false, null, Instant.now().toString(), null));
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
                context.scheduleExecutor(),
                context.taskRepository());
        wireEnqueueToDoSubmit(taskRunService, context.scheduleExecutor());
        return taskRunService;
    }
}
