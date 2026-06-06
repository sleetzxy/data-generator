package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.JobSubmitRequest;
import com.datagenerator.web.dto.JobSubmitResult;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.storage.JobRepository;
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
class JobScheduleExecutorTest {

    private static final String CONFIG_PATH = "jobs/demo.yaml";

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobService jobService;

    private JobScheduleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new JobScheduleExecutor(jobRepository, jobService);
    }

    @Test
    void enqueue_whenRunning_queuesSecondJob() {
        JobSubmitRequest request = submitRequest();
        JobSubmitResult firstResult = new JobSubmitResult(
                new JobResponse("job-1", JobStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t1", null, null),
                true);
        JobResponse queued = new JobResponse(
                "job-2", JobStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t2", null, null);
        queued.setTriggerSource(TriggerSource.MANUAL);

        when(jobRepository.findRunningByJobConfig(CONFIG_PATH))
                .thenReturn(List.of())
                .thenReturn(List.of(runningJob("job-1")));
        when(jobService.doSubmit(request, TriggerSource.MANUAL)).thenReturn(firstResult);
        when(jobService.createQueuedJob(CONFIG_PATH, TriggerSource.MANUAL)).thenReturn(queued);
        when(jobRepository.findById("job-2")).thenReturn(Optional.of(queued));

        JobSubmitResult first = executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, request);
        JobSubmitResult second = executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, request);

        assertThat(first.async()).isTrue();
        assertThat(first.response().getJobId()).isEqualTo("job-1");
        assertThat(second.async()).isTrue();
        assertThat(second.response().getJobId()).isEqualTo("job-2");
        assertThat(second.response().getStatus()).isEqualTo(JobStatus.PENDING);

        verify(jobService).doSubmit(request, TriggerSource.MANUAL);
        verify(jobService).createQueuedJob(CONFIG_PATH, TriggerSource.MANUAL);
    }

    @Test
    void onJobTerminal_dequeuesNext() {
        JobSubmitRequest firstRequest = submitRequest();
        JobSubmitRequest queuedRequest = submitRequest();
        JobSubmitResult firstResult = new JobSubmitResult(
                new JobResponse("job-1", JobStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t1", null, null),
                true);
        JobResponse queued = new JobResponse(
                "job-2", JobStatus.PENDING, emptyProgress(), List.of(), null, CONFIG_PATH, "t2", null, null);

        when(jobRepository.findRunningByJobConfig(CONFIG_PATH))
                .thenReturn(List.of())
                .thenReturn(List.of(runningJob("job-1")));
        when(jobService.doSubmit(firstRequest, TriggerSource.MANUAL)).thenReturn(firstResult);
        when(jobService.createQueuedJob(CONFIG_PATH, TriggerSource.MANUAL)).thenReturn(queued);
        when(jobRepository.findById("job-2")).thenReturn(Optional.of(queued));

        executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, firstRequest);
        executor.enqueue(CONFIG_PATH, TriggerSource.MANUAL, queuedRequest);

        executor.onJobTerminal(CONFIG_PATH);

        ArgumentCaptor<String> jobIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JobSubmitRequest> requestCaptor = ArgumentCaptor.forClass(JobSubmitRequest.class);
        verify(jobService).executeAccepted(jobIdCaptor.capture(), requestCaptor.capture());
        assertThat(jobIdCaptor.getValue()).isEqualTo("job-2");
        assertThat(requestCaptor.getValue()).isSameAs(queuedRequest);
    }

    @Test
    void onJobTerminal_emptyQueue_doesNothing() {
        executor.onJobTerminal(CONFIG_PATH);
        verify(jobService, never()).executeAccepted(any(), any());
    }

    private static JobSubmitRequest submitRequest() {
        JobSubmitRequest request = new JobSubmitRequest();
        request.setJobConfig(CONFIG_PATH);
        request.setWriter(Map.of("type", "csv", "path", "out.csv"));
        return request;
    }

    private static JobResponse runningJob(String jobId) {
        return new JobResponse(
                jobId,
                JobStatus.RUNNING,
                emptyProgress(),
                List.of(),
                null,
                CONFIG_PATH,
                "t",
                null,
                null);
    }

    private static JobProgress emptyProgress() {
        return new JobProgress(0, 0, 0, 0, 0);
    }
}
