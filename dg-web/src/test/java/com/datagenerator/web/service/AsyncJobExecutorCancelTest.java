package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncJobExecutorCancelTest {

    private JobServiceTestSupport.JobServiceContext context;
    private AsyncJobExecutor executor;

    @BeforeEach
    void setUp() {
        context = JobServiceTestSupport.createContext(new JobRuntimeSettings(5000, 1000, 2));
        executor = context.asyncJobExecutor();
    }

    @Test
    void cancel_pendingJob_marksCancelled() throws InterruptedException {
        String jobId = "job-cancel-test";
        executor.submit(jobId, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        awaitStatus(jobId, JobStatus.RUNNING, JobStatus.PENDING);
        assertThat(executor.cancel(jobId)).isTrue();
        assertThat(context.jobRepository().findById(jobId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void cancel_unknownJob_returnsFalse() {
        assertThat(executor.cancel("missing")).isFalse();
    }

    private void awaitStatus(String jobId, JobStatus... acceptable) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var response = context.jobRepository().findById(jobId);
            if (response.isPresent()) {
                for (JobStatus status : acceptable) {
                    if (response.get().getStatus() == status) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
    }
}
