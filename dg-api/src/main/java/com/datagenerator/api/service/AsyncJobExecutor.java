package com.datagenerator.api.service;

import com.datagenerator.api.dto.JobProgress;
import com.datagenerator.api.dto.JobResponse;
import com.datagenerator.api.dto.JobStatus;
import com.datagenerator.api.dto.TableDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 异步任务执行器：后台线程池执行造数任务并更新内存 JobStore。
 */
public class AsyncJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobExecutor.class);

    private final ExecutorService executor;
    private final ConcurrentHashMap<String, JobResponse> jobs;
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public AsyncJobExecutor(int threadPoolSize, ConcurrentHashMap<String, JobResponse> jobs) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, threadPoolSize));
        this.jobs = jobs;
    }

    public void submit(String jobId, Runnable task) {
        jobs.put(jobId, pending(jobId));
        Future<?> future = executor.submit(() -> {
            if (cancelled.contains(jobId)) {
                jobs.put(jobId, cancelled(jobId));
                return;
            }
            jobs.put(jobId, running(jobId));
            try {
                if (cancelled.contains(jobId)) {
                    jobs.put(jobId, cancelled(jobId));
                    return;
                }
                task.run();
            } catch (Exception exception) {
                if (cancelled.contains(jobId)) {
                    jobs.put(jobId, cancelled(jobId));
                    return;
                }
                log.error("Async job {} failed", jobId, exception);
                jobs.put(jobId, failed(jobId, exception.getMessage()));
            }
        });
        futures.put(jobId, future);
    }

    public boolean cancel(String jobId) {
        JobResponse current = jobs.get(jobId);
        if (current == null) {
            return false;
        }
        if (current.getStatus() != JobStatus.PENDING && current.getStatus() != JobStatus.RUNNING) {
            return false;
        }
        cancelled.add(jobId);
        Future<?> future = futures.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }
        jobs.put(jobId, cancelled(jobId));
        log.info("Cancelled async job {}", jobId);
        return true;
    }

    private static JobResponse pending(String jobId) {
        return new JobResponse(jobId, JobStatus.PENDING, emptyProgress(), List.of(), null, null);
    }

    private static JobResponse running(String jobId) {
        return new JobResponse(jobId, JobStatus.RUNNING, emptyProgress(), List.of(), null, null);
    }

    private static JobResponse cancelled(String jobId) {
        return new JobResponse(jobId, JobStatus.CANCELLED, emptyProgress(), List.of(), null, null);
    }

    private static JobResponse failed(String jobId, String message) {
        TableDetail detail = new TableDetail("_error", 0, 0, message);
        return new JobResponse(jobId, JobStatus.FAILED, emptyProgress(), List.of(detail), null, null);
    }

    private static JobProgress emptyProgress() {
        return new JobProgress(0, 0, 0, 0, 0);
    }
}
