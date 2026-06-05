package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.exception.JobNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncJobExecutorCancelTest {

    @Test
    void cancel_pendingJob_marksCancelled() throws InterruptedException {
        ConcurrentHashMap<String, com.datagenerator.web.dto.JobResponse> jobs = new ConcurrentHashMap<>();
        JobLogStore logStore = new JobLogStore();
        AsyncJobExecutor executor = new AsyncJobExecutor(2, jobs, logStore);
        String jobId = "job-cancel-test";

        executor.submit(jobId, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        awaitStatus(jobs, jobId, JobStatus.RUNNING, JobStatus.PENDING);
        assertThat(executor.cancel(jobId)).isTrue();
        assertThat(jobs.get(jobId).getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void cancel_unknownJob_returnsFalse() {
        AsyncJobExecutor executor = new AsyncJobExecutor(1, new ConcurrentHashMap<>(), new JobLogStore());
        assertThat(executor.cancel("missing")).isFalse();
    }

    private static void awaitStatus(
            ConcurrentHashMap<String, com.datagenerator.web.dto.JobResponse> jobs,
            String jobId,
            JobStatus... acceptable) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            com.datagenerator.web.dto.JobResponse response = jobs.get(jobId);
            if (response != null) {
                for (JobStatus status : acceptable) {
                    if (response.getStatus() == status) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
    }
}
