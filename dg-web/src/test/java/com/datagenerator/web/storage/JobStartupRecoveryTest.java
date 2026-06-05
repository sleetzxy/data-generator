package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.service.JobLogStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobStartupRecoveryTest {

    private JobRepository jobRepository;
    private JobLogStore jobLogStore;
    private JobStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = SqliteTestSupport.createInMemoryJdbcTemplate();
        jobRepository = new JobRepository(jdbc, SqliteTestSupport.objectMapper());
        jobLogStore = new JobLogStore(new JobLogRepository(jdbc));
        recovery = new JobStartupRecovery(jobRepository, jobLogStore);
    }

    @Test
    void recover_marksRunningJobCancelledAndWritesLog() {
        jobRepository.insert(sampleJob("job-run", JobStatus.RUNNING));

        recovery.recover();

        assertThat(jobRepository.findById("job-run").orElseThrow().getStatus())
                .isEqualTo(JobStatus.CANCELLED);
        assertThat(jobLogStore.getLogs("job-run"))
                .anyMatch(entry -> entry.getMessage().contains("服务重启"));
    }

    @Test
    void recover_marksPendingJobCancelled() {
        jobRepository.insert(sampleJob("job-pending", JobStatus.PENDING));

        recovery.recover();

        assertThat(jobRepository.findById("job-pending").orElseThrow().getStatus())
                .isEqualTo(JobStatus.CANCELLED);
    }

    private static JobResponse sampleJob(String jobId, JobStatus status) {
        return new JobResponse(
                jobId,
                status,
                new JobProgress(0, 0, 0, 0, 0),
                List.of(new TableDetail("t1", 0, "pending")),
                null,
                "jobs/test.yaml",
                "2026-06-05T00:00:00Z",
                null,
                null);
    }
}
