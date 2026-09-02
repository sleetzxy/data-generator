package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.TableTask;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TaskRunServiceWriterResolutionTest {

    private TaskRunService taskRunService;

    @BeforeEach
    void setUp() {
        TaskRunRuntimeSettings runtimeSettings = new TaskRunRuntimeSettings(5000, 1000, 2);
        TaskRunServiceTestSupport.TaskRunServiceContext context = TaskRunServiceTestSupport.createContext(runtimeSettings);
        taskRunService = new TaskRunService(
                mock(TaskRunOrchestrator.class),
                mock(PreviewOrchestratorFactory.class),
                mock(YamlConfigLoader.class),
                mock(ConstraintLoader.class),
                new ConnectionRegistry(),
                runtimeSettings,
                context.taskRunRepository(),
                context.taskRunLogRepository(),
                context.asyncTaskRunExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor(),
                context.taskRepository());
    }

    @Test
    void resolveRuntimeWriters_jobYamlOnly_usesJobWriter() throws Exception {
        TaskConfig taskConfig = taskConfigWithTable("orders", Map.of());
        taskConfig.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        List<Map<String, Object>> resolved = invokeResolveRuntimeWriters(taskConfig, requestWithWriter(Map.of()));

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveRuntimeWriters_jobWritersOverrideRequestWriters() throws Exception {
        TaskConfig taskConfig = taskConfigWithTable("orders", Map.of());
        taskConfig.setWriters(List.of(
                Map.of("type", "postgresql", "connection", "pg", "mode", "insert"),
                Map.of("type", "clickhouse", "connection", "ck", "mode", "insert")));

        List<Map<String, Object>> resolved = invokeResolveRuntimeWriters(
                taskConfig,
                requestWithWriters(List.of(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"))));

        assertThat(resolved).containsExactly(
                Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));
    }

    @Test
    void resolveRuntimeWriters_tableWriterOverridesJobWriter() throws Exception {
        TaskConfig taskConfig = taskConfigWithTable(
                "orders",
                Map.of("type", "csv", "connection", "traffic-output", "mode", "insert"));
        taskConfig.setWriter(Map.of("type", "csv", "connection", "local-csv", "mode", "insert"));

        invokeResolveRuntimeWriters(taskConfig, requestWithWriter(Map.of()));
    }

    @Test
    void resolveRuntimeWriters_missingTableWriter_throws() {
        TaskConfig taskConfig = taskConfigWithTable("orders", Map.of());

        assertThatThrownBy(() -> invokeResolveRuntimeWriters(taskConfig, requestWithWriter(Map.of())))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause()
                .hasMessageContaining("orders");
    }

    private static TaskConfig taskConfigWithTable(String tableName, Map<String, Object> tableWriter) {
        TaskConfig taskConfig = new TaskConfig();
        TableTask table = new TableTask();
        table.setName(tableName);
        table.setCount(10);
        table.setWriter(tableWriter);
        taskConfig.setTables(List.of(table));
        return taskConfig;
    }

    private static TaskRunSubmitRequest requestWithWriter(Map<String, Object> writer) {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setWriter(writer);
        return request;
    }

    private static TaskRunSubmitRequest requestWithWriters(List<Map<String, Object>> writers) {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setWriters(writers);
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeResolveRuntimeWriters(
            TaskConfig taskConfig, TaskRunSubmitRequest request) throws Exception {
        var method = TaskRunService.class.getDeclaredMethod(
                "resolveRuntimeWriters", TaskConfig.class, TaskRunSubmitRequest.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(taskRunService, taskConfig, request);
    }
}
