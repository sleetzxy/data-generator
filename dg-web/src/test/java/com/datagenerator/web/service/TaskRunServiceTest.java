package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunIndexResponse;
import com.datagenerator.web.dto.TaskRunListFilter;
import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatsResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.TaskRepository;
import com.datagenerator.web.storage.TaskRunRepository;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaskRunServiceTest {

    private TaskRunOrchestrator orchestrator;
    private TaskRunService taskRunService;
    private TaskRunRepository taskRunRepository;
    private TaskRunServiceTestSupport.TaskRunServiceContext context;

    @BeforeEach
    void setUp() {
        orchestrator = mock(TaskRunOrchestrator.class);
        TaskRunRuntimeSettings runtimeSettings = new TaskRunRuntimeSettings(5000, 1000, 2);
        context = TaskRunServiceTestSupport.createContext(runtimeSettings);
        taskRunRepository = context.taskRunRepository();
        taskRunService = new TaskRunService(
                orchestrator,
                mock(PreviewOrchestratorFactory.class),
                mock(YamlConfigLoader.class),
                mock(ConstraintLoader.class),
                mock(ConnectionRegistry.class),
                runtimeSettings,
                taskRunRepository,
                context.taskRunLogRepository(),
                context.asyncTaskRunExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor(),
                context.taskRepository());
        TaskRunServiceTestSupport.wireEnqueueToDoSubmit(taskRunService, context.scheduleExecutor());
    }

    @Test
    void createQueuedRun_insertsPendingWithoutExecuting() {
        com.datagenerator.web.dto.TaskRunResponse response =
                taskRunService.createQueuedRun("task-configs/demo.yaml", TriggerSource.MANUAL);

        assertThat(response.getStatus()).isEqualTo(TaskRunStatus.PENDING);
        assertThat(response.getConfigPath()).isEqualTo("task-configs/demo.yaml");
        assertThat(response.getTriggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(taskRunService.getById(response.getRunId()).getStatus()).isEqualTo(TaskRunStatus.PENDING);
        verify(orchestrator, never()).run(any(), anyList(), any());
    }

    @Test
    void stats_withNoRuns_returnsZeroCountsAndFullDailyWindow() {
        TaskRunStatsResponse stats = taskRunService.stats();

        assertThat(stats.totalRuns()).isZero();
        assertThat(stats.running()).isZero();
        assertThat(stats.totalWritten()).isZero();
        assertThat(stats.topConfigs()).isEmpty();
        assertThat(stats.daily()).hasSize(14);
        assertThat(stats.daily().get(13).date())
                .isEqualTo(LocalDate.now(ZoneId.systemDefault()).toString());
    }

    @Test
    void stats_withRuns_aggregatesCountsAndVolume() {
        insertRun("r1", "task-configs/a.yaml", TaskRunStatus.COMPLETED, 30, "2026-08-01T00:00:00Z");
        insertRun("r2", "task-configs/a.yaml", TaskRunStatus.COMPLETED, 10, "2026-08-02T00:00:00Z");
        insertRun("r3", "task-configs/b.yaml", TaskRunStatus.RUNNING, 5, "2026-08-03T00:00:00Z");

        TaskRunStatsResponse stats = taskRunService.stats();

        assertThat(stats.totalRuns()).isEqualTo(3);
        assertThat(stats.completed()).isEqualTo(2);
        assertThat(stats.running()).isEqualTo(1);
        assertThat(stats.totalWritten()).isEqualTo(45);
        assertThat(stats.topConfigs()).extracting(item -> item.configPath())
                .containsExactly("task-configs/a.yaml", "task-configs/b.yaml");
        assertThat(stats.topConfigs().get(0).writtenRows()).isEqualTo(40);
    }

    @Test
    void stats_topConfigs_resolvesDisplayNameFromTasksTable() {
        // a.yaml 对应主表行 display_name 为 "演示任务A"；b.yaml 无主表行，模拟任务已删除
        insertTask("a", "演示任务A");
        insertRun("r1", "task-configs/a.yaml", TaskRunStatus.COMPLETED, 30, "2026-08-01T00:00:00Z");
        insertRun("r2", "task-configs/a.yaml", TaskRunStatus.COMPLETED, 10, "2026-08-02T00:00:00Z");
        insertRun("r3", "task-configs/b.yaml", TaskRunStatus.RUNNING, 5, "2026-08-03T00:00:00Z");

        TaskRunStatsResponse stats = taskRunService.stats();

        assertThat(stats.topConfigs().get(0).displayName()).isEqualTo("演示任务A");
        assertThat(stats.topConfigs().get(0).runCount()).isEqualTo(2);
        assertThat(stats.topConfigs().get(1).displayName()).isNull();
    }

    @Test
    void runIndexes_aggregatesLatestAndActivePerConfigPath() {
        insertRun("r1", "task-configs/a.yaml", TaskRunStatus.COMPLETED, 0, "2026-08-01T00:00:00Z");
        insertRun("r2", "task-configs/a.yaml", TaskRunStatus.RUNNING, 0, "2026-08-02T00:00:00Z");
        insertRun("r3", "task-configs/b.yaml", TaskRunStatus.COMPLETED, 0, "2026-08-03T00:00:00Z");

        TaskRunIndexResponse indexes = taskRunService.runIndexes();

        assertThat(indexes.latestRuns()).extracting(item -> item.getRunId())
                .containsExactlyInAnyOrder("r2", "r3");
        assertThat(indexes.activeRuns()).extracting(item -> item.getRunId())
                .containsExactly("r2");
    }

    @Test
    void list_withMultipleStatuses_returnsRunsOfAllStatuses() {
        insertRun("r1", "task-configs/a.yaml", TaskRunStatus.RUNNING, 0, "2026-08-01T00:00:00Z");
        insertRun("r2", "task-configs/a.yaml", TaskRunStatus.PENDING, 0, "2026-08-02T00:00:00Z");
        insertRun("r3", "task-configs/b.yaml", TaskRunStatus.COMPLETED, 0, "2026-08-03T00:00:00Z");

        var response = taskRunService.list(1, 10, new TaskRunListFilter("RUNNING,PENDING", null, null, null));

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getItems()).extracting(item -> item.getRunId())
                .containsExactlyInAnyOrder("r1", "r2");
    }

    @Test
    void submit_withUnknownConfigPath_throwsNotFound() {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath("task-configs/nonexistent.yaml");

        assertThatThrownBy(() -> taskRunService.submit(request))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void list_resolvesDisplayNameFromTasksTable() {
        insertTask("alpha", "演示任务A");
        insertRun("r1", "task-configs/alpha.yaml", TaskRunStatus.COMPLETED, 0, "2026-09-02T00:00:00Z");

        var response = taskRunService.list(1, 10, null);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getRunId()).isEqualTo("r1");
        assertThat(response.getItems().get(0).getDisplayName()).isEqualTo("演示任务A");
    }

    @Test
    void list_taskDeleted_displayNameNull() {
        // ghost.yaml 对应任务已被删除（任务主表无行），displayName 保持 null 由前端回退
        insertRun("r1", "task-configs/ghost.yaml", TaskRunStatus.COMPLETED, 0, "2026-09-02T00:00:00Z");

        var response = taskRunService.list(1, 10, null);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getDisplayName()).isNull();
    }

    /** 预插任务主表行（调度关闭），供 config_path 解析测试使用 */
    private void insertTask(String fileName, String displayName) {
        context.taskRepository().insert(new TaskRepository.TaskRecord(
                fileName, fileName, displayName, false, null,
                "2026-09-02T10:00:00Z", null));
    }

    private void insertRun(String runId, String configPath, TaskRunStatus status, long writtenRows, String submittedAt) {
        TaskRunResponse taskRun = new TaskRunResponse(
                runId,
                status,
                new TaskRunProgress(1, 1, 100, writtenRows, 0),
                List.of(),
                null,
                configPath,
                submittedAt,
                null,
                null);
        taskRunRepository.insert(taskRun);
    }
}
