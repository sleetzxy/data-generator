package com.datagenerator.web.storage;

import com.datagenerator.web.dto.ConfigVolumeStat;
import com.datagenerator.web.dto.DailyRunStat;
import com.datagenerator.web.dto.TaskRunListFilter;
import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TableDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunRepositoryTest {

    private TaskRunRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new TaskRunRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
    }

    @Test
    void insert_and_findById_returnsTaskRun() {
        TaskRunResponse taskRun = sampleTaskRun("task-1", TaskRunStatus.PENDING);
        repository.insert(taskRun);

        Optional<TaskRunResponse> found = repository.findById("task-1");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TaskRunStatus.PENDING);
        assertThat(found.get().getConfigPath()).isEqualTo("task-configs/test.yaml");
    }

    @Test
    void update_persistsStatusAndProgress() {
        repository.insert(sampleTaskRun("task-2", TaskRunStatus.PENDING));
        TaskRunResponse running = sampleTaskRun("task-2", TaskRunStatus.RUNNING);
        running.setProgress(new TaskRunProgress(2, 1, 100, 50, 0));
        running.setDuration("1.2s");

        repository.update(running);

        TaskRunResponse found = repository.findById("task-2").orElseThrow();
        assertThat(found.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(found.getProgress().getWrittenRows()).isEqualTo(50);
        assertThat(found.getDuration()).isEqualTo("1.2s");
    }

    @Test
    void listAll_ordersBySubmittedAtDesc() {
        TaskRunResponse older = sampleTaskRun("task-old", TaskRunStatus.COMPLETED);
        older.setSubmittedAt("2026-01-01T00:00:00Z");
        TaskRunResponse newer = sampleTaskRun("task-new", TaskRunStatus.COMPLETED);
        newer.setSubmittedAt("2026-06-01T00:00:00Z");
        repository.insert(older);
        repository.insert(newer);

        List<TaskRunResponse> all = repository.listAll();
        assertThat(all).extracting(TaskRunResponse::getRunId).containsExactly("task-new", "task-old");
    }

    @Test
    void delete_removesTaskRun() {
        repository.insert(sampleTaskRun("task-del", TaskRunStatus.COMPLETED));
        repository.delete("task-del");
        assertThat(repository.findById("task-del")).isEmpty();
    }

    @Test
    void findRunningByConfigPath_returnsOnlyRunning() {
        insertTaskRun("r1", "task-configs/a.yaml", TaskRunStatus.RUNNING);
        insertTaskRun("r2", "task-configs/a.yaml", TaskRunStatus.COMPLETED);

        assertThat(repository.findRunningByConfigPath("task-configs/a.yaml"))
                .extracting(TaskRunResponse::getRunId)
                .containsExactly("r1");
    }

    @Test
    void findByStatusIn_returnsMatchingTaskRuns() {
        repository.insert(sampleTaskRun("task-run", TaskRunStatus.RUNNING));
        repository.insert(sampleTaskRun("task-done", TaskRunStatus.COMPLETED));

        assertThat(repository.findByStatusIn(List.of(TaskRunStatus.RUNNING, TaskRunStatus.PENDING)))
                .extracting(TaskRunResponse::getRunId)
                .containsExactly("task-run");
    }

    @Test
    void listPage_and_countAll_supportPagination() {
        for (int index = 0; index < 5; index++) {
            TaskRunResponse taskRun = sampleTaskRun("task-" + index, TaskRunStatus.COMPLETED);
            taskRun.setSubmittedAt("2026-06-0" + (index + 1) + "T00:00:00Z");
            repository.insert(taskRun);
        }

        assertThat(repository.countAll(null)).isEqualTo(5);
        assertThat(repository.listPage(0, 2, null)).hasSize(2);
        assertThat(repository.listPage(0, 2, null).get(0).getRunId()).isEqualTo("task-4");
    }

    @Test
    void listPage_withStatusFilter_returnsOnlyMatching() {
        insertTaskRun("r1", "task-configs/a.yaml", TaskRunStatus.RUNNING);
        insertTaskRun("r2", "task-configs/a.yaml", TaskRunStatus.COMPLETED);
        insertTaskRun("r3", "task-configs/b.yaml", TaskRunStatus.RUNNING);

        TaskRunListFilter filter = new TaskRunListFilter("RUNNING", null, null, null);

        assertThat(repository.countAll(filter)).isEqualTo(2);
        assertThat(repository.listPage(0, 10, filter)).extracting(TaskRunResponse::getRunId)
                .containsExactlyInAnyOrder("r1", "r3");
    }

    @Test
    void listPage_withMultipleStatuses_returnsMatchingAll() {
        insertTaskRun("r1", "task-configs/a.yaml", TaskRunStatus.RUNNING);
        insertTaskRun("r2", "task-configs/a.yaml", TaskRunStatus.PENDING);
        insertTaskRun("r3", "task-configs/b.yaml", TaskRunStatus.COMPLETED);

        TaskRunListFilter filter = new TaskRunListFilter("RUNNING,PENDING", null, null, null);

        assertThat(repository.countAll(filter)).isEqualTo(2);
        assertThat(repository.listPage(0, 10, filter)).extracting(TaskRunResponse::getRunId)
                .containsExactlyInAnyOrder("r1", "r2");
    }

    @Test
    void listPage_withConfigPathAndTimeRange_combinesConditions() {
        insertTaskRun("r1", "task-configs/a.yaml", TaskRunStatus.COMPLETED);
        insertTaskRun("r2", "task-configs/b.yaml", TaskRunStatus.COMPLETED);
        TaskRunResponse later = sampleTaskRun("r3", TaskRunStatus.COMPLETED);
        later.setConfigPath("task-configs/a.yaml");
        later.setSubmittedAt("2026-07-01T00:00:00Z");
        repository.insert(later);

        TaskRunListFilter filter = new TaskRunListFilter(
                null, "task-configs/a.yaml", "2026-06-01T00:00:00Z", "2026-06-30T23:59:59Z");

        assertThat(repository.countAll(filter)).isEqualTo(1);
        assertThat(repository.listPage(0, 10, filter)).extracting(TaskRunResponse::getRunId)
                .containsExactly("r1");
    }

    @Test
    void listPage_withTimeRange_includesWholeSecondRecordAtBoundary() {
        // 整秒格式（纳秒为 0 时省略小数）的记录不应被 to 边界错误排除
        TaskRunResponse wholeSecond = sampleTaskRun("r-whole-second", TaskRunStatus.COMPLETED);
        wholeSecond.setSubmittedAt("2026-06-05T23:59:59Z");
        repository.insert(wholeSecond);
        TaskRunResponse withFraction = sampleTaskRun("r-with-fraction", TaskRunStatus.COMPLETED);
        withFraction.setSubmittedAt("2026-06-05T00:00:00.500Z");
        repository.insert(withFraction);

        TaskRunListFilter filter = new TaskRunListFilter(
                null, null, "2026-06-05T00:00:00Z", "2026-06-05T23:59:59.999Z");

        assertThat(repository.countAll(filter)).isEqualTo(2);
    }

    @Test
    void countByStatus_groupsByStatus() {
        insertTaskRun("r1", "task-configs/a.yaml", TaskRunStatus.COMPLETED);
        insertTaskRun("r2", "task-configs/a.yaml", TaskRunStatus.COMPLETED);
        insertTaskRun("r3", "task-configs/b.yaml", TaskRunStatus.RUNNING);

        Map<String, Long> counts = repository.countByStatus();

        assertThat(counts).containsEntry("COMPLETED", 2L).containsEntry("RUNNING", 1L);
    }

    @Test
    void sumWrittenRows_sumsAllRuns() {
        TaskRunResponse run1 = sampleTaskRun("r1", TaskRunStatus.COMPLETED);
        run1.setProgress(new TaskRunProgress(1, 1, 100, 40, 0));
        repository.insert(run1);
        TaskRunResponse run2 = sampleTaskRun("r2", TaskRunStatus.COMPLETED);
        run2.setProgress(new TaskRunProgress(1, 1, 100, 60, 0));
        repository.insert(run2);

        assertThat(repository.sumWrittenRows()).isEqualTo(100);
    }

    @Test
    void topWrittenByConfigPath_ordersByWrittenRowsDesc() {
        insertTaskRunWithWritten("r1", "task-configs/a.yaml", 10);
        insertTaskRunWithWritten("r2", "task-configs/b.yaml", 30);
        insertTaskRunWithWritten("r3", "task-configs/a.yaml", 5);
        insertTaskRunWithWritten("r4", "task-configs/c.yaml", 20);

        List<ConfigVolumeStat> top = repository.topWrittenByConfigPath(2);

        assertThat(top).containsExactly(
                new ConfigVolumeStat("task-configs/b.yaml", 1, 30),
                new ConfigVolumeStat("task-configs/c.yaml", 1, 20));
    }

    @Test
    void dailyRunStats_aggregatesByDay() {
        TaskRunResponse day1 = sampleTaskRun("r1", TaskRunStatus.COMPLETED);
        day1.setSubmittedAt("2026-08-20T10:00:00Z");
        day1.setProgress(new TaskRunProgress(1, 1, 10, 10, 0));
        repository.insert(day1);
        TaskRunResponse day2 = sampleTaskRun("r2", TaskRunStatus.COMPLETED);
        day2.setSubmittedAt("2026-08-22T10:00:00Z");
        day2.setProgress(new TaskRunProgress(1, 1, 20, 20, 0));
        repository.insert(day2);

        List<DailyRunStat> daily = repository.dailyRunStats("2026-08-20");

        assertThat(daily).containsExactly(
                new DailyRunStat("2026-08-20", 1, 10),
                new DailyRunStat("2026-08-22", 1, 20));
    }

    @Test
    void latestRunsByConfigPath_returnsLatestPerPath() {
        insertTaskRunWithTime("r1", "task-configs/a.yaml", "2026-06-01T00:00:00Z");
        insertTaskRunWithTime("r2", "task-configs/a.yaml", "2026-06-02T00:00:00Z");
        insertTaskRunWithTime("r3", "task-configs/b.yaml", "2026-06-03T00:00:00Z");

        assertThat(repository.latestRunsByConfigPath())
                .extracting(TaskRunResponse::getRunId)
                .containsExactlyInAnyOrder("r2", "r3");
    }

    @Test
    void activeRunsByConfigPath_returnsOnlyActiveLatest() {
        insertTaskRunWithTime("r1", "task-configs/a.yaml", "2026-06-01T00:00:00Z");
        TaskRunResponse running = sampleTaskRun("r2", TaskRunStatus.RUNNING);
        running.setConfigPath("task-configs/a.yaml");
        running.setSubmittedAt("2026-06-02T00:00:00Z");
        repository.insert(running);
        insertTaskRunWithTime("r3", "task-configs/b.yaml", "2026-06-03T00:00:00Z");

        assertThat(repository.activeRunsByConfigPath())
                .extracting(TaskRunResponse::getRunId)
                .containsExactly("r2");
    }

    private void insertTaskRun(String runId, String configPath, TaskRunStatus status) {
        TaskRunResponse taskRun = sampleTaskRun(runId, status);
        taskRun.setConfigPath(configPath);
        repository.insert(taskRun);
    }

    private void insertTaskRunWithTime(String runId, String configPath, String submittedAt) {
        TaskRunResponse taskRun = sampleTaskRun(runId, TaskRunStatus.COMPLETED);
        taskRun.setConfigPath(configPath);
        taskRun.setSubmittedAt(submittedAt);
        repository.insert(taskRun);
    }

    private void insertTaskRunWithWritten(String runId, String configPath, long writtenRows) {
        TaskRunResponse taskRun = sampleTaskRun(runId, TaskRunStatus.COMPLETED);
        taskRun.setConfigPath(configPath);
        taskRun.setProgress(new TaskRunProgress(1, 1, 100, writtenRows, 0));
        repository.insert(taskRun);
    }

    private static TaskRunResponse sampleTaskRun(String runId, TaskRunStatus status) {
        return new TaskRunResponse(
                runId,
                status,
                new TaskRunProgress(1, 0, 10, 0, 0),
                List.of(new TableDetail("t1", 0, "pending")),
                null,
                "task-configs/test.yaml",
                "2026-06-05T00:00:00Z",
                null,
                null);
    }
}
