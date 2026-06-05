package com.datagenerator.api.service;

import com.datagenerator.api.config.JobRuntimeSettings;
import com.datagenerator.api.dto.JobStatus;
import com.datagenerator.api.exception.JobNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncJobExecutorCancelTest {

    @Test
    void cancel_pendingJob_marksCancelled() throws InterruptedException {
        ConcurrentHashMap<String, com.datagenerator.api.dto.JobResponse> jobs = new ConcurrentHashMap<>();
        AsyncJobExecutor executor = new AsyncJobExecutor(2, jobs);
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
        AsyncJobExecutor executor = new AsyncJobExecutor(1, new ConcurrentHashMap<>());
        assertThat(executor.cancel("missing")).isFalse();
    }

    private static void awaitStatus(
            ConcurrentHashMap<String, com.datagenerator.api.dto.JobResponse> jobs,
            String jobId,
            JobStatus... acceptable) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            com.datagenerator.api.dto.JobResponse response = jobs.get(jobId);
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
