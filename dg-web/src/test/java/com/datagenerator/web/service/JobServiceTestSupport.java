package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.storage.JobLogRepository;
import com.datagenerator.web.storage.JobRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JobServiceTestSupport {

    private JobServiceTestSupport() {
    }

    public record JobServiceContext(
            JobRepository jobRepository,
            JobLogStore jobLogStore,
            AsyncJobExecutor asyncJobExecutor,
            JobCancellationRegistry cancellationRegistry) {}

    public static JobServiceContext createContext(JobRuntimeSettings runtimeSettings) {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        JobRepository jobRepository = new JobRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
        JobLogStore jobLogStore = new JobLogStore(new JobLogRepository(jdbcTemplate));
        JobCancellationRegistry cancellationRegistry = new JobCancellationRegistry();
        AsyncJobExecutor asyncJobExecutor = new AsyncJobExecutor(
                runtimeSettings, jobRepository, jobLogStore, cancellationRegistry);
        return new JobServiceContext(jobRepository, jobLogStore, asyncJobExecutor, cancellationRegistry);
    }
}
