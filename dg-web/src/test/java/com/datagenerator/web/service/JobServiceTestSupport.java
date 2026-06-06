package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.storage.JobLogRepository;
import com.datagenerator.web.storage.JobRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class JobServiceTestSupport {

    private JobServiceTestSupport() {
    }

    public record JobServiceContext(
            JobRepository jobRepository,
            JobLogStore jobLogStore,
            AsyncJobExecutor asyncJobExecutor,
            JobCancellationRegistry cancellationRegistry,
            JobScheduleExecutor scheduleExecutor) {}

    public static JobServiceContext createContext(JobRuntimeSettings runtimeSettings) {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        JobRepository jobRepository = new JobRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
        JobLogStore jobLogStore = new JobLogStore(new JobLogRepository(jdbcTemplate));
        JobCancellationRegistry cancellationRegistry = new JobCancellationRegistry();
        AsyncJobExecutor asyncJobExecutor = new AsyncJobExecutor(
                runtimeSettings, jobRepository, jobLogStore, cancellationRegistry);
        JobScheduleExecutor scheduleExecutor = mock(JobScheduleExecutor.class);
        return new JobServiceContext(
                jobRepository, jobLogStore, asyncJobExecutor, cancellationRegistry, scheduleExecutor);
    }

    public static void wireEnqueueToDoSubmit(JobService jobService, JobScheduleExecutor scheduleExecutor) {
        when(scheduleExecutor.enqueue(any(), any(), any())).thenAnswer(invocation -> jobService.doSubmit(
                invocation.getArgument(2),
                invocation.getArgument(1)));
    }

    public static JobService createJobService(
            JobRuntimeSettings runtimeSettings,
            JobOrchestrator orchestrator,
            YamlConfigLoader configLoader) {
        JobServiceContext context = createContext(runtimeSettings);
        JobService jobService = new JobService(
                orchestrator,
                mock(PreviewJobOrchestratorFactory.class),
                configLoader,
                mock(ConstraintLoader.class),
                mock(ConnectionRegistry.class),
                runtimeSettings,
                context.jobRepository(),
                context.jobLogStore(),
                context.asyncJobExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
        wireEnqueueToDoSubmit(jobService, context.scheduleExecutor());
        return jobService;
    }
}
