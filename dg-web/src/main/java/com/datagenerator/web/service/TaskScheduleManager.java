package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.storage.TaskScheduleRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Component
public class TaskScheduleManager {

    private final ThreadPoolTaskScheduler scheduler;
    private final TaskScheduleService scheduleService;
    private final TaskRunQueueExecutor executor;
    private final TaskConfigService definitionService;
    private final TaskScheduleRepository scheduleRepository;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public TaskScheduleManager(
            ThreadPoolTaskScheduler scheduler,
            TaskScheduleService scheduleService,
            TaskRunQueueExecutor executor,
            TaskConfigService definitionService,
            TaskScheduleRepository scheduleRepository) {
        this.scheduler = scheduler;
        this.scheduleService = scheduleService;
        this.executor = executor;
        this.definitionService = definitionService;
        this.scheduleRepository = scheduleRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void onReady() {
        reloadAll();
    }

    public void reloadAll() {
        futures.values().forEach(future -> future.cancel(false));
        futures.clear();

        List<TaskConfigResponse> definitions = definitionService.list().items();
        Set<String> configPaths = definitions.stream()
                .map(TaskConfigResponse::getPath)
                .collect(Collectors.toSet());
        scheduleRepository.deleteOrphans(configPaths);

        for (TaskConfigResponse definition : definitions) {
            reschedule(definition.getPath(), definition.isBuiltin());
        }
    }

    public void reschedule(String configPath) {
        boolean builtin = definitionService.list().items().stream()
                .filter(definition -> definition.getPath().equals(configPath))
                .findFirst()
                .map(TaskConfigResponse::isBuiltin)
                .orElse(false);
        reschedule(configPath, builtin);
    }

    public void cancel(String configPath) {
        ScheduledFuture<?> future = futures.remove(configPath);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void reschedule(String configPath, boolean builtin) {
        cancel(configPath);

        TaskScheduleResponse schedule = scheduleService.resolveSchedule(configPath, builtin);
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

    private void fireScheduled(String configPath) {
        TaskRunSubmitRequest request = new TaskRunSubmitRequest();
        request.setConfigPath(configPath);
        executor.enqueue(configPath, TriggerSource.SCHEDULED, request);
    }
}
