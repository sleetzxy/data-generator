package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.TaskRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class TaskScheduleService {

    private final TaskRepository taskRepository;

    public TaskScheduleService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public TaskScheduleResponse resolveSchedule(String configPath) {
        TaskRepository.TaskRecord task = requireTask(configPath);
        return toResponse(task.scheduleEnabled(), task.scheduleCron());
    }

    public TaskScheduleRequest validateAndNormalize(TaskScheduleRequest request) {
        // 校验逻辑与原实现一致：enabled 时 cron 必填且合法；
        // disabled 时 cron 可空但若给出须合法
        boolean enabled = request.isEnabled();
        String cron = normalizeCron(request.getCron());

        if (enabled) {
            if (cron == null) {
                throw new IllegalArgumentException(
                        "Cron expression is required when schedule is enabled");
            }
            if (!CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException("Invalid cron expression: " + cron);
            }
        } else if (cron != null && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }

        TaskScheduleRequest normalized = new TaskScheduleRequest();
        normalized.setEnabled(enabled);
        normalized.setCron(cron);
        return normalized;
    }

    public String computeNextRunAt(String cron) {
        // 原逻辑保留
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            return null;
        }
        CronExpression expression = CronExpression.parse(cron);
        LocalDateTime next = expression.next(LocalDateTime.now());
        if (next == null) {
            return null;
        }
        return next.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
    }

    public TaskScheduleResponse saveSchedule(String configPath, TaskScheduleRequest request) {
        requireTask(configPath);
        TaskScheduleRequest normalized = validateAndNormalize(request);
        persistSchedule(configPath, normalized);
        return toResponse(normalized.isEnabled(), normalized.getCron());
    }

    public void persistSchedule(String configPath, TaskScheduleRequest normalized) {
        taskRepository.updateSchedule(
                TaskConfigPaths.toFileName(configPath),
                normalized.isEnabled(),
                normalized.getCron(),
                Instant.now().toString());
    }

    private TaskRepository.TaskRecord requireTask(String configPath) {
        String fileName = TaskConfigPaths.toFileName(configPath);
        return taskRepository.findByFileName(fileName)
                .orElseThrow(() -> new TaskConfigNotFoundException(
                        "Task config not found: " + fileName));
    }

    private TaskScheduleResponse toResponse(boolean enabled, String cron) {
        String nextRunAt = enabled ? computeNextRunAt(cron) : null;
        return new TaskScheduleResponse(enabled, cron, nextRunAt);
    }

    private String normalizeCron(String cron) {
        // 原逻辑保留
        if (cron == null) {
            return null;
        }
        String trimmed = cron.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
