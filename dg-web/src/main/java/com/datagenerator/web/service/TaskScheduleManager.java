package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.storage.TaskRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class TaskScheduleManager {

    private final ThreadPoolTaskScheduler scheduler;
    private final TaskScheduleService scheduleService;
    private final TaskRunQueueExecutor executor;
    private final TaskRepository taskRepository;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public TaskScheduleManager(
            ThreadPoolTaskScheduler scheduler,
            TaskScheduleService scheduleService,
            TaskRunQueueExecutor executor,
            TaskRepository taskRepository) {
        this.scheduler = scheduler;
        this.scheduleService = scheduleService;
        this.executor = executor;
        this.taskRepository = taskRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void onReady() {
        reloadAll();
    }

    public void reloadAll() {
        futures.values().forEach(future -> future.cancel(false));
        futures.clear();

        for (TaskRepository.TaskRecord task : taskRepository.findAllEnabledSchedules()) {
            reschedule(TaskConfigPaths.toConfigPath(task.fileName()));
        }
    }

    public void reschedule(String configPath) {
        cancel(configPath);
        TaskScheduleResponse schedule = scheduleService.resolveSchedule(configPath);
        if (!schedule.isEnabled() || schedule.getCron() == null) {
            return;
        }
        if (!CronExpression.isValidExpression(schedule.getCron())) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fireScheduled(configPath),
                new CronTrigger(schedule.getCron()));
        futures.put(configPath, future);
    }

    public void cancel(String configPath) {
        ScheduledFuture<?> future = futures.remove(configPath);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void fireScheduled(String configPath) {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath(configPath);
        executor.enqueue(configPath, TriggerSource.SCHEDULED, request);
    }
}
