package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobSubmitRequest;
import com.datagenerator.web.dto.JobSubmitResult;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.storage.JobRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 job 配置路径串行化执行：同一 config 同时仅一个 RUNNING，其余 FIFO 排队。
 */
@Component
public class JobScheduleExecutor {

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final ConcurrentHashMap<String, JobScheduleQueue> queues = new ConcurrentHashMap<>();

    public JobScheduleExecutor(JobRepository jobRepository, @Lazy JobService jobService) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
    }

    public JobSubmitResult enqueue(String configPath, TriggerSource trigger, JobSubmitRequest request) {
        JobScheduleQueue queue = queues.computeIfAbsent(configPath, ignored -> new JobScheduleQueue());
        synchronized (queue) {
            boolean hasRunning = !jobRepository.findRunningByJobConfig(configPath).isEmpty();
            if (!hasRunning && queue.items.isEmpty()) {
                return jobService.doSubmit(request, trigger);
            }
            JobResponse queued = jobService.createQueuedJob(configPath, trigger);
            queue.items.add(new QueuedItem(queued.getJobId(), trigger, request));
            JobResponse pending = jobRepository.findById(queued.getJobId()).orElseThrow();
            return new JobSubmitResult(pending, true);
        }
    }

    public void onJobTerminal(String configPath) {
        JobScheduleQueue queue = queues.get(configPath);
        if (queue == null) {
            return;
        }
        synchronized (queue) {
            QueuedItem next = queue.items.poll();
            if (next != null) {
                jobService.executeAccepted(next.jobId(), next.request());
            }
        }
    }

    public void clearQueue(String configPath) {
        queues.remove(configPath);
    }

    private static final class JobScheduleQueue {
        private final Queue<QueuedItem> items = new ArrayDeque<>();
    }

    private record QueuedItem(String jobId, TriggerSource trigger, JobSubmitRequest request) {
    }
}
