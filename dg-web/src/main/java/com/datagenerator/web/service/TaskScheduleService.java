package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.ScheduleDefinition;
import com.datagenerator.core.model.YamlConfigLoader;
import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.exception.ReadOnlyScheduleException;
import com.datagenerator.web.storage.TaskScheduleRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
public class TaskScheduleService {

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final TaskScheduleRepository scheduleRepository;

    public TaskScheduleService(ConfigPathResolver pathResolver, TaskScheduleRepository scheduleRepository) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
        this.scheduleRepository = scheduleRepository;
    }

    public TaskScheduleResponse resolveSchedule(String configPath, boolean builtin) {
        if (builtin) {
            TaskConfig job = configLoader.loadJob(configPath);
            Optional<ScheduleDefinition> schedule = job.getSchedule();
            if (schedule.isEmpty()) {
                return toResponse(false, null, false);
            }
            ScheduleDefinition definition = schedule.get();
            return toResponse(definition.isEnabled(), normalizeCron(definition.getCron()), false);
        }

        Optional<TaskScheduleRepository.TaskScheduleRecord> record =
                scheduleRepository.findByConfigPath(configPath);
        if (record.isEmpty()) {
            return toResponse(false, null, true);
        }
        TaskScheduleRepository.TaskScheduleRecord stored = record.get();
        return toResponse(stored.enabled(), normalizeCron(stored.cron()), true);
    }

    public TaskScheduleRequest validateAndNormalize(TaskScheduleRequest request) {
        boolean enabled = request.isEnabled();
        String cron = normalizeCron(request.getCron());

        if (enabled) {
            if (cron == null) {
                throw new IllegalArgumentException("Cron expression is required when schedule is enabled");
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
        if (isBuiltin(configPath)) {
            throw new ReadOnlyScheduleException(configPath);
        }
        TaskScheduleRequest normalized = validateAndNormalize(request);
        persistSchedule(configPath, normalized);
        return toResponse(normalized.isEnabled(), normalized.getCron(), true);
    }

    public void persistSchedule(String configPath, TaskScheduleRequest normalized) {
        scheduleRepository.upsert(
                configPath,
                normalized.isEnabled(),
                normalized.getCron(),
                Instant.now().toString());
    }

    private TaskScheduleResponse toResponse(boolean enabled, String cron, boolean editable) {
        String nextRunAt = enabled ? computeNextRunAt(cron) : null;
        return new TaskScheduleResponse(enabled, cron, editable, nextRunAt);
    }

    private boolean isBuiltin(String configPath) {
        return pathResolver.existsOnClasspath(configPath);
    }

    private String normalizeCron(String cron) {
        if (cron == null) {
            return null;
        }
        String trimmed = cron.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
