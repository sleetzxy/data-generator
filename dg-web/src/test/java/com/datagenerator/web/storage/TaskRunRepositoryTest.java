package com.datagenerator.web.storage;

import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TableDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
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
    void insert_and_findById_returnsJob() {
        TaskRunResponse taskRun = sampleTaskRun("job-1", TaskRunStatus.PENDING);
        repository.insert(taskRun);

        Optional<TaskRunResponse> found = repository.findById("job-1");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TaskRunStatus.PENDING);
        assertThat(found.get().getConfigPath()).isEqualTo("task-configs/test.yaml");
    }

    @Test
    void update_persistsStatusAndProgress() {
        repository.insert(sampleTaskRun("job-2", TaskRunStatus.PENDING));
        TaskRunResponse running = sampleTaskRun("job-2", TaskRunStatus.RUNNING);
        running.setProgress(new TaskRunProgress(2, 1, 100, 50, 0));
        running.setDuration("1.2s");

        repository.update(running);

        TaskRunResponse found = repository.findById("job-2").orElseThrow();
        assertThat(found.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(found.getProgress().getWrittenRows()).isEqualTo(50);
        assertThat(found.getDuration()).isEqualTo("1.2s");
    }

    @Test
    void listAll_ordersBySubmittedAtDesc() {
        TaskRunResponse older = sampleTaskRun("job-old", TaskRunStatus.COMPLETED);
        older.setSubmittedAt("2026-01-01T00:00:00Z");
        TaskRunResponse newer = sampleTaskRun("job-new", TaskRunStatus.COMPLETED);
        newer.setSubmittedAt("2026-06-01T00:00:00Z");
        repository.insert(older);
        repository.insert(newer);

        List<TaskRunResponse> all = repository.listAll();
        assertThat(all).extracting(TaskRunResponse::getRunId).containsExactly("job-new", "job-old");
    }

    @Test
    void delete_removesJob() {
        repository.insert(sampleTaskRun("job-del", TaskRunStatus.COMPLETED));
        repository.delete("job-del");
        assertThat(repository.findById("job-del")).isEmpty();
    }

    @Test
    void findRunningByConfigPath_returnsOnlyRunning() {
        insertJob("j1", "task-configs/a.yaml", TaskRunStatus.RUNNING);
        insertJob("j2", "task-configs/a.yaml", TaskRunStatus.COMPLETED);

        assertThat(repository.findRunningByConfigPath("task-configs/a.yaml"))
                .extracting(TaskRunResponse::getRunId)
                .containsExactly("j1");
    }

    @Test
    void findByStatusIn_returnsMatchingJobs() {
        repository.insert(sampleTaskRun("job-run", TaskRunStatus.RUNNING));
        repository.insert(sampleTaskRun("job-done", TaskRunStatus.COMPLETED));

        assertThat(repository.findByStatusIn(List.of(TaskRunStatus.RUNNING, TaskRunStatus.PENDING)))
                .extracting(TaskRunResponse::getRunId)
                .containsExactly("job-run");
    }

    @Test
    void listPage_and_countAll_supportPagination() {
        for (int index = 0; index < 5; index++) {
            TaskRunResponse taskRun = sampleTaskRun("job-" + index, TaskRunStatus.COMPLETED);
            taskRun.setSubmittedAt("2026-06-0" + (index + 1) + "T00:00:00Z");
            repository.insert(taskRun);
        }

        assertThat(repository.countAll()).isEqualTo(5);
        assertThat(repository.listPage(0, 2)).hasSize(2);
        assertThat(repository.listPage(0, 2).get(0).getRunId()).isEqualTo("job-4");
    }

    private void insertJob(String runId, String configPath, TaskRunStatus status) {
        TaskRunResponse taskRun = sampleTaskRun(runId, status);
        taskRun.setConfigPath(configPath);
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
