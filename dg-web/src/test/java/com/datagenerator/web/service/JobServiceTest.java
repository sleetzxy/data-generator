package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JobServiceTest {

    private JobOrchestrator orchestrator;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        orchestrator = mock(JobOrchestrator.class);
        JobRuntimeSettings runtimeSettings = new JobRuntimeSettings(5000, 1000, 2);
        JobServiceTestSupport.JobServiceContext context = JobServiceTestSupport.createContext(runtimeSettings);
        jobService = new JobService(
                orchestrator,
                mock(PreviewJobOrchestratorFactory.class),
                mock(YamlConfigLoader.class),
                mock(ConstraintLoader.class),
                mock(ConnectionRegistry.class),
                runtimeSettings,
                context.jobRepository(),
                context.jobLogStore(),
                context.asyncJobExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
    }

    @Test
    void createQueuedJob_insertsPendingWithoutExecuting() {
        com.datagenerator.web.dto.JobResponse response =
                jobService.createQueuedJob("jobs/demo.yaml", TriggerSource.MANUAL);

        assertThat(response.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(response.getJobConfig()).isEqualTo("jobs/demo.yaml");
        assertThat(response.getTriggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(jobService.getById(response.getJobId()).getStatus()).isEqualTo(JobStatus.PENDING);
        verify(orchestrator, never()).run(any(), anyList(), any());
    }
}
