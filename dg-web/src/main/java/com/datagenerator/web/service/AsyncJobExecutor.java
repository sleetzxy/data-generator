package com.datagenerator.web.service;

import com.datagenerator.core.engine.JobCancelledException;
import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.storage.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务执行器：后台线程池执行造数任务并持久化状态。
 */
@Component
public class AsyncJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobExecutor.class);

    private final ExecutorService executor;
    private final JobRepository jobRepository;
    private final JobLogStore jobLogStore;
    private final JobCancellationRegistry cancellationRegistry;
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    public AsyncJobExecutor(
            JobRuntimeSettings runtimeSettings,
            JobRepository jobRepository,
            JobLogStore jobLogStore,
            JobCancellationRegistry cancellationRegistry) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, runtimeSettings.threadPoolSize()));
        this.jobRepository = jobRepository;
        this.jobLogStore = jobLogStore;
        this.cancellationRegistry = cancellationRegistry;
    }

    public void submit(String jobId, Runnable task) {
        JobResponse current = jobRepository.findById(jobId).orElseGet(() -> {
            JobResponse created = new JobResponse(
                    jobId,
                    JobStatus.PENDING,
                    emptyProgress(),
                    List.of(),
                    null,
                    null,
                    Instant.now().toString(),
                    null,
                    null);
            jobRepository.insert(created);
            return created;
        });
        persistStatus(current, JobStatus.PENDING);
        Future<?> future = executor.submit(() -> {
            try {
                if (cancellationRegistry.isCancelled(jobId)) {
                    markCancelled(jobId);
                    jobLogStore.warn(jobId, "任务在启动前已取消");
                    return;
                }
                persistStatus(loadJob(jobId), JobStatus.RUNNING);
                jobLogStore.info(jobId, "任务开始执行");
                if (cancellationRegistry.isCancelled(jobId)) {
                    markCancelled(jobId);
                    jobLogStore.warn(jobId, "任务已取消");
                    return;
                }
                task.run();
            } catch (JobCancelledException cancelled) {
                markCancelled(jobId);
                jobLogStore.warn(jobId, "任务已取消");
            } catch (Exception exception) {
                if (cancellationRegistry.isCancelled(jobId)) {
                    markCancelled(jobId);
                    jobLogStore.warn(jobId, "任务已取消");
                    return;
                }
                JobResponse latest = loadJob(jobId);
                if (latest.getStatus() == JobStatus.RUNNING || latest.getStatus() == JobStatus.PENDING) {
                    log.error("Async job {} failed", jobId, exception);
                    jobLogStore.error(jobId, "任务执行失败: " + exception.getMessage());
                    latest.setStatus(JobStatus.FAILED);
                    latest.setErrorMessage(exception.getMessage());
                    latest.setDetails(List.of(new TableDetail("_error", 0, 0, exception.getMessage())));
                    jobRepository.update(latest);
                }
            } finally {
                futures.remove(jobId);
                cancellationRegistry.clear(jobId);
            }
        });
        futures.put(jobId, future);
    }

    public boolean cancel(String jobId) {
        JobResponse current = loadJobOrNull(jobId);
        if (current == null) {
            return false;
        }
        if (current.getStatus() != JobStatus.PENDING && current.getStatus() != JobStatus.RUNNING) {
            return false;
        }
        Future<?> future = futures.get(jobId);
        if (future == null) {
            return false;
        }
        cancellationRegistry.markCancelled(jobId);
        futures.remove(jobId);
        future.cancel(true);
        persistStatus(current, JobStatus.CANCELLED);
        jobLogStore.warn(jobId, "任务已被用户取消");
        log.info("Cancelled async job {}", jobId);
        return true;
    }

    public boolean isCancelled(String jobId) {
        return cancellationRegistry.isCancelled(jobId);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private JobResponse loadJob(String jobId) {
        return jobRepository.findById(jobId).orElseThrow(
                () -> new IllegalStateException("Job not found: " + jobId));
    }

    private JobResponse loadJobOrNull(String jobId) {
        return jobRepository.findById(jobId).orElse(null);
    }

    private void markCancelled(String jobId) {
        JobResponse current = loadJobOrNull(jobId);
        if (current != null) {
            persistStatus(current, JobStatus.CANCELLED);
        }
    }

    private void persistStatus(JobResponse current, JobStatus status) {
        if (current.getStatus() == JobStatus.CANCELLED && status != JobStatus.CANCELLED) {
            return;
        }
        current.setStatus(status);
        jobRepository.update(current);
    }

    private static JobProgress emptyProgress() {
        return new JobProgress(0, 0, 0, 0, 0);
    }
}
