package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TableDetail;
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
    private final JobLogStore jobLogStore;
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public AsyncJobExecutor(
            int threadPoolSize,
            ConcurrentHashMap<String, JobResponse> jobs,
            JobLogStore jobLogStore) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, threadPoolSize));
        this.jobs = jobs;
        this.jobLogStore = jobLogStore;
    }

    public void submit(String jobId, Runnable task) {
        JobResponse current = jobs.get(jobId);
        if (current == null) {
            current = new JobResponse(
                    jobId, JobStatus.PENDING, emptyProgress(), List.of(), null, null, null, null, null);
        }
        jobs.put(jobId, pending(current));
        Future<?> future = executor.submit(() -> {
            if (cancelled.contains(jobId)) {
                jobs.put(jobId, cancelled(jobs.get(jobId)));
                jobLogStore.warn(jobId, "任务在启动前已取消");
                return;
            }
            jobs.put(jobId, running(jobs.get(jobId)));
            jobLogStore.info(jobId, "任务开始执行");
            try {
                if (cancelled.contains(jobId)) {
                    jobs.put(jobId, cancelled(jobs.get(jobId)));
                    jobLogStore.warn(jobId, "任务已取消");
                    return;
                }
                task.run();
            } catch (Exception exception) {
                if (cancelled.contains(jobId)) {
                    jobs.put(jobId, cancelled(jobs.get(jobId)));
                    jobLogStore.warn(jobId, "任务已取消");
                    return;
                }
                log.error("Async job {} failed", jobId, exception);
                jobLogStore.error(jobId, "任务执行失败: " + exception.getMessage());
                jobs.put(jobId, failed(jobs.get(jobId), exception.getMessage()));
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
        jobs.put(jobId, cancelled(current));
        jobLogStore.warn(jobId, "任务已被用户取消");
        log.info("Cancelled async job {}", jobId);
        return true;
    }

    private static JobResponse pending(JobResponse current) {
        return withStatus(current, JobStatus.PENDING);
    }

    private static JobResponse running(JobResponse current) {
        return withStatus(current, JobStatus.RUNNING);
    }

    private static JobResponse cancelled(JobResponse current) {
        return withStatus(current, JobStatus.CANCELLED);
    }

    private static JobResponse failed(JobResponse current, String message) {
        JobResponse response = withStatus(current, JobStatus.FAILED);
        response.setErrorMessage(message);
        TableDetail detail = new TableDetail("_error", 0, 0, message);
        response.setDetails(List.of(detail));
        return response;
    }

    private static JobResponse withStatus(JobResponse current, JobStatus status) {
        if (current == null) {
            return new JobResponse(null, status, emptyProgress(), List.of(), null, null, null, null, null);
        }
        current.setStatus(status);
        return current;
    }

    private static JobProgress emptyProgress() {
        return new JobProgress(0, 0, 0, 0, 0);
    }
}
