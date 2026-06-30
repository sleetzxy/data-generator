package com.datagenerator.web.service;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.storage.JobLogFileRepository;
import com.datagenerator.web.storage.JobRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

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
        return createContext(runtimeSettings, Path.of(System.getProperty("java.io.tmpdir"), "dg-test-logs"));
    }

    public static JobServiceContext createContext(JobRuntimeSettings runtimeSettings, Path logDir) {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        JobRepository jobRepository = new JobRepository(jdbcTemplate, SqliteTestSupport.objectMapper());
        DataGeneratorProperties properties = new DataGeneratorProperties();
        properties.getStorage().setLogDir(logDir.toString());
        JobLogStore jobLogStore = new JobLogStore(new JobLogFileRepository(properties));
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
                new ConnectionRegistry(),
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
