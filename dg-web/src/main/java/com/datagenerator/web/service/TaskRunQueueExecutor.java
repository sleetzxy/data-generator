package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskRunSubmitResult;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.storage.TaskRunRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按配置路径串行化执行：同一 config 同时仅一个 RUNNING，其余 FIFO 排队。
 */
@Component
public class TaskRunQueueExecutor {

    private final TaskRunRepository taskRunRepository;
    private final TaskRunService taskRunService;
    private final ConcurrentHashMap<String, TaskRunQueue> queues = new ConcurrentHashMap<>();

    public TaskRunQueueExecutor(TaskRunRepository taskRunRepository, @Lazy TaskRunService taskRunService) {
        this.taskRunRepository = taskRunRepository;
        this.taskRunService = taskRunService;
    }

    public TaskRunSubmitResult enqueue(String configPath, TriggerSource trigger, TaskRunSubmitRequest request) {
        TaskRunQueue queue = queues.computeIfAbsent(configPath, ignored -> new TaskRunQueue());
        synchronized (queue) {
            boolean hasRunning = !taskRunRepository.findRunningByConfigPath(configPath).isEmpty();
            if (!hasRunning && queue.items.isEmpty()) {
                return taskRunService.doSubmit(request, trigger);
            }
            TaskRunResponse queued = taskRunService.createQueuedRun(configPath, trigger);
            queue.items.add(new QueuedItem(queued.getRunId(), trigger, request));
            TaskRunResponse pending = taskRunRepository.findById(queued.getRunId()).orElseThrow();
            return new TaskRunSubmitResult(pending, true);
        }
    }

    public void onRunTerminal(String configPath) {
        TaskRunQueue queue = queues.get(configPath);
        if (queue == null) {
            return;
        }
        synchronized (queue) {
            QueuedItem next = queue.items.poll();
            if (next != null) {
                taskRunService.executeAccepted(next.runId(), next.request());
            }
        }
    }

    public void clearQueue(String configPath) {
        queues.remove(configPath);
    }

    private static final class TaskRunQueue {
        private final Queue<QueuedItem> items = new ArrayDeque<>();
    }

    private record QueuedItem(String runId, TriggerSource trigger, TaskRunSubmitRequest request) {
    }
}
