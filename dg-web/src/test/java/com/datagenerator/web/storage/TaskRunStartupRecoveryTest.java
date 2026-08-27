package com.datagenerator.web.storage;

import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.config.DataGeneratorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunStartupRecoveryTest {

    @TempDir
    Path tempDir;

    private TaskRunRepository taskRunRepository;
    private TaskRunLogRepository taskRunLogRepository;
    private TaskRunStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = SqliteTestSupport.createInMemoryJdbcTemplate();
        taskRunRepository = new TaskRunRepository(jdbc, SqliteTestSupport.objectMapper());
        DataGeneratorProperties properties = new DataGeneratorProperties();
        properties.getStorage().setLogDir(tempDir.toString());
        taskRunLogRepository = new TaskRunLogRepository(properties);
        recovery = new TaskRunStartupRecovery(taskRunRepository, taskRunLogRepository);
    }

    @Test
    void recover_marksRunningJobCancelledAndWritesLog() {
        taskRunRepository.insert(sampleJob("job-run", TaskRunStatus.RUNNING));

        recovery.recover();

        assertThat(taskRunRepository.findById("job-run").orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(taskRunLogRepository.getLogs("job-run"))
                .anyMatch(entry -> entry.getMessage().contains("服务重启"));
    }

    @Test
    void recover_marksPendingJobCancelled() {
        taskRunRepository.insert(sampleJob("job-pending", TaskRunStatus.PENDING));

        recovery.recover();

        assertThat(taskRunRepository.findById("job-pending").orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.CANCELLED);
    }

    private static TaskRunResponse sampleJob(String runId, TaskRunStatus status) {
        return new TaskRunResponse(
                runId,
                status,
                new TaskRunProgress(0, 0, 0, 0, 0),
                List.of(new TableDetail("t1", 0, "pending")),
                null,
                "jobs/test.yaml",
                "2026-06-05T00:00:00Z",
                null,
                null);
    }
}
