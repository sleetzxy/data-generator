package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TableDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryTest {

    private JobRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new JobRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
    }

    @Test
    void insert_and_findById_returnsJob() {
        JobResponse job = sampleJob("job-1", JobStatus.PENDING);
        repository.insert(job);

        Optional<JobResponse> found = repository.findById("job-1");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(found.get().getJobConfig()).isEqualTo("jobs/test.yaml");
    }

    @Test
    void update_persistsStatusAndProgress() {
        repository.insert(sampleJob("job-2", JobStatus.PENDING));
        JobResponse running = sampleJob("job-2", JobStatus.RUNNING);
        running.setProgress(new JobProgress(2, 1, 100, 50, 0));
        running.setDuration("1.2s");

        repository.update(running);

        JobResponse found = repository.findById("job-2").orElseThrow();
        assertThat(found.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(found.getProgress().getWrittenRows()).isEqualTo(50);
        assertThat(found.getDuration()).isEqualTo("1.2s");
    }

    @Test
    void listAll_ordersBySubmittedAtDesc() {
        JobResponse older = sampleJob("job-old", JobStatus.COMPLETED);
        older.setSubmittedAt("2026-01-01T00:00:00Z");
        JobResponse newer = sampleJob("job-new", JobStatus.COMPLETED);
        newer.setSubmittedAt("2026-06-01T00:00:00Z");
        repository.insert(older);
        repository.insert(newer);

        List<JobResponse> all = repository.listAll();
        assertThat(all).extracting(JobResponse::getJobId).containsExactly("job-new", "job-old");
    }

    @Test
    void delete_removesJob() {
        repository.insert(sampleJob("job-del", JobStatus.COMPLETED));
        repository.delete("job-del");
        assertThat(repository.findById("job-del")).isEmpty();
    }

    @Test
    void findByStatusIn_returnsMatchingJobs() {
        repository.insert(sampleJob("job-run", JobStatus.RUNNING));
        repository.insert(sampleJob("job-done", JobStatus.COMPLETED));

        assertThat(repository.findByStatusIn(List.of(JobStatus.RUNNING, JobStatus.PENDING)))
                .extracting(JobResponse::getJobId)
                .containsExactly("job-run");
    }

    private static JobResponse sampleJob(String jobId, JobStatus status) {
        return new JobResponse(
                jobId,
                status,
                new JobProgress(1, 0, 10, 0, 0),
                List.of(new TableDetail("t1", 0, "pending")),
                null,
                "jobs/test.yaml",
                "2026-06-05T00:00:00Z",
                null,
                null);
    }
}
