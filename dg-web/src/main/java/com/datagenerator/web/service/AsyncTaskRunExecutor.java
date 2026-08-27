package com.datagenerator.web.service;

import com.datagenerator.core.engine.JobCancelledException;
import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.storage.TaskRunLogRepository;
import com.datagenerator.web.storage.TaskRunRepository;
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
public class AsyncTaskRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskRunExecutor.class);

    private final ExecutorService executor;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunLogRepository taskRunLogRepository;
    private final TaskRunCancellationRegistry cancellationRegistry;
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    public AsyncTaskRunExecutor(
            TaskRunRuntimeSettings runtimeSettings,
            TaskRunRepository taskRunRepository,
            TaskRunLogRepository taskRunLogRepository,
            TaskRunCancellationRegistry cancellationRegistry) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, runtimeSettings.threadPoolSize()));
        this.taskRunRepository = taskRunRepository;
        this.taskRunLogRepository = taskRunLogRepository;
        this.cancellationRegistry = cancellationRegistry;
    }

    public void submit(String runId, Runnable task) {
        TaskRunResponse current = taskRunRepository.findById(runId).orElseGet(() -> {
            TaskRunResponse created = new TaskRunResponse(
                    runId,
                    TaskRunStatus.PENDING,
                    emptyProgress(),
                    List.of(),
                    null,
                    null,
                    Instant.now().toString(),
                    null,
                    null);
            taskRunRepository.insert(created);
            return created;
        });
        persistStatus(current, TaskRunStatus.PENDING);
        Future<?> future = executor.submit(() -> {
            try {
                if (cancellationRegistry.isCancelled(runId)) {
                    markCancelled(runId);
                    taskRunLogRepository.warn(runId, "任务在启动前已取消");
                    return;
                }
                persistStatus(loadRun(runId), TaskRunStatus.RUNNING);
                taskRunLogRepository.info(runId, "任务开始执行");
                if (cancellationRegistry.isCancelled(runId)) {
                    markCancelled(runId);
                    taskRunLogRepository.warn(runId, "任务已取消");
                    return;
                }
                task.run();
            } catch (JobCancelledException cancelled) {
                markCancelled(runId);
                taskRunLogRepository.warn(runId, "任务已取消");
            } catch (Exception exception) {
                if (cancellationRegistry.isCancelled(runId)) {
                    markCancelled(runId);
                    taskRunLogRepository.warn(runId, "任务已取消");
                    return;
                }
                TaskRunResponse latest = loadRun(runId);
                if (latest.getStatus() == TaskRunStatus.RUNNING || latest.getStatus() == TaskRunStatus.PENDING) {
                    log.error("Async task run {} failed", runId, exception);
                    taskRunLogRepository.error(runId, "任务执行失败: " + exception.getMessage());
                    latest.setStatus(TaskRunStatus.FAILED);
                    latest.setErrorMessage(exception.getMessage());
                    latest.setDetails(List.of(new TableDetail("_error", 0, 0, exception.getMessage())));
                    taskRunRepository.update(latest);
                }
            } finally {
                futures.remove(runId);
                cancellationRegistry.clear(runId);
            }
        });
        futures.put(runId, future);
    }

    public boolean cancel(String runId) {
        TaskRunResponse current = loadRunOrNull(runId);
        if (current == null) {
            return false;
        }
        if (current.getStatus() != TaskRunStatus.PENDING && current.getStatus() != TaskRunStatus.RUNNING) {
            return false;
        }
        Future<?> future = futures.get(runId);
        if (future == null) {
            return false;
        }
        cancellationRegistry.markCancelled(runId);
        futures.remove(runId);
        future.cancel(true);
        persistStatus(current, TaskRunStatus.CANCELLED);
        taskRunLogRepository.warn(runId, "任务已被用户取消");
        log.info("Cancelled async task run {}", runId);
        return true;
    }

    public boolean isCancelled(String runId) {
        return cancellationRegistry.isCancelled(runId);
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

    private TaskRunResponse loadRun(String runId) {
        return taskRunRepository.findById(runId).orElseThrow(
                () -> new IllegalStateException("Task run not found: " + runId));
    }

    private TaskRunResponse loadRunOrNull(String runId) {
        return taskRunRepository.findById(runId).orElse(null);
    }

    private void markCancelled(String runId) {
        TaskRunResponse current = loadRunOrNull(runId);
        if (current != null) {
            persistStatus(current, TaskRunStatus.CANCELLED);
        }
    }

    private void persistStatus(TaskRunResponse current, TaskRunStatus status) {
        if (current.getStatus() == TaskRunStatus.CANCELLED && status != TaskRunStatus.CANCELLED) {
            return;
        }
        current.setStatus(status);
        taskRunRepository.update(current);
    }

    private static TaskRunProgress emptyProgress() {
        return new TaskRunProgress(0, 0, 0, 0, 0);
    }
}
