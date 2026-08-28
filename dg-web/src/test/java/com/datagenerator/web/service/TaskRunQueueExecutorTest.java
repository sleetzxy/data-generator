package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskRunSubmitResult;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.storage.TaskRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRunQueueExecutorTest {

    private static final String CONFIG_PATH = "task-configs/demo.yaml";

    @Mock
    private TaskRunRepository jobRepository;

    @Mock
    private TaskRunService taskRunService;

    private TaskRunQueueExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TaskRunQueueExecutor(jobRepository, taskRunService);
    }

    @Test
    void enqueue_whenRunning_queuesSecondJob() {
        TaskRunSubmitRequest request = submitRequest();
        TaskRunSubmitResult firstResult = new TaskRunSubmitResult(
                new TaskRunResponse("job-1", TaskRunStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t1", null, null),
                true);
        TaskRunResponse queued = new TaskRunResponse(
                "job-2", TaskRunStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t2", null, null);
        queued.setTriggerSource(TriggerSource.MANUAL);

        when(jobRepository.findRunningByConfigPath(CONFIG_PATH))
                .thenReturn(List.of())
                .thenReturn(List.of(runningJob("job-1")));
        when(taskRunService.doSubmit(request, TriggerSource.MANUAL)).thenReturn(firstResult);
        when(taskRunService.createQueuedJob(CONFIG_PATH, TriggerSource.MANUAL)).thenReturn(queued);
        when(jobRepository.findById("job-2")).thenReturn(Optional.of(queued));

        TaskRunSubmitResult first = executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, request);
        TaskRunSubmitResult second = executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, request);

        assertThat(first.async()).isTrue();
        assertThat(first.response().getRunId()).isEqualTo("job-1");
        assertThat(second.async()).isTrue();
        assertThat(second.response().getRunId()).isEqualTo("job-2");
        assertThat(second.response().getStatus()).isEqualTo(TaskRunStatus.PENDING);

        verify(taskRunService).doSubmit(request, TriggerSource.MANUAL);
        verify(taskRunService).createQueuedJob(CONFIG_PATH, TriggerSource.MANUAL);
    }

    @Test
    void onRunTerminal_dequeuesNext() {
        TaskRunSubmitRequest firstRequest = submitRequest();
        TaskRunSubmitRequest queuedRequest = submitRequest();
        TaskRunSubmitResult firstResult = new TaskRunSubmitResult(
                new TaskRunResponse("job-1", TaskRunStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t1", null, null),
                true);
        TaskRunResponse queued = new TaskRunResponse(
                "job-2", TaskRunStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t2", null, null);

        when(jobRepository.findRunningByConfigPath(CONFIG_PATH))
                .thenReturn(List.of())
                .thenReturn(List.of(runningJob("job-1")));
        when(taskRunService.doSubmit(firstRequest, TriggerSource.MANUAL)).thenReturn(firstResult);
        when(taskRunService.createQueuedJob(CONFIG_PATH, TriggerSource.MANUAL)).thenReturn(queued);
        when(jobRepository.findById("job-2")).thenReturn(Optional.of(queued));

        executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, firstRequest);
        executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, queuedRequest);

        executor.onRunTerminal(CONFIG_PATH);

        ArgumentCaptor<String> jobIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TaskRunSubmitRequest> requestCaptor = ArgumentCaptor.forClass(TaskRunSubmitRequest.class);
        verify(taskRunService).executeAccepted(jobIdCaptor.capture(), requestCaptor.capture());
        assertThat(jobIdCaptor.getValue()).isEqualTo("job-2");
        assertThat(requestCaptor.getValue()).isSameAs(queuedRequest);
    }

    @Test
    void onRunTerminal_emptyQueue_doesNothing() {
        executor.onRunTerminal(CONFIG_PATH);
        verify(taskRunService, never()).executeAccepted(any(), any());
    }

    private static TaskRunSubmitRequest submitRequest() {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath(CONFIG_PATH);
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));
        return request;
    }

    private static TaskRunResponse runningJob(String runId) {
        return new TaskRunResponse(
                runId,
                TaskRunStatus.RUNNING,
                emptyProgress(),
                List.of(),
                null,
                CONFIG_PATH,
                "t",
                null,
                null);
    }

    private static TaskRunProgress emptyProgress() {
        return new TaskRunProgress(0, 0, 0, 0, 0);
    }
}
