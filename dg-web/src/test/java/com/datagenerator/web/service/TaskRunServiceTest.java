package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.model.YamlConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaskRunServiceTest {

    private JobOrchestrator orchestrator;
    private TaskRunService taskRunService;

    @BeforeEach
    void setUp() {
        orchestrator = mock(JobOrchestrator.class);
        TaskRunRuntimeSettings runtimeSettings = new TaskRunRuntimeSettings(5000, 1000, 2);
        TaskRunServiceTestSupport.TaskRunServiceContext context = TaskRunServiceTestSupport.createContext(runtimeSettings);
        taskRunService = new TaskRunService(
                orchestrator,
                mock(PreviewOrchestratorFactory.class),
                mock(YamlConfigLoader.class),
                mock(ConstraintLoader.class),
                mock(ConnectionRegistry.class),
                runtimeSettings,
                context.taskRunRepository(),
                context.taskRunLogRepository(),
                context.asyncTaskRunExecutor(),
                context.cancellationRegistry(),
                context.scheduleExecutor());
    }

    @Test
    void createQueuedJob_insertsPendingWithoutExecuting() {
        com.datagenerator.web.dto.TaskRunResponse response =
                taskRunService.createQueuedJob("jobs/demo.yaml", TriggerSource.MANUAL);

        assertThat(response.getStatus()).isEqualTo(TaskRunStatus.PENDING);
        assertThat(response.getConfigPath()).isEqualTo("jobs/demo.yaml");
        assertThat(response.getTriggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(taskRunService.getById(response.getRunId()).getStatus()).isEqualTo(TaskRunStatus.PENDING);
        verify(orchestrator, never()).run(any(), anyList(), any());
    }
}
