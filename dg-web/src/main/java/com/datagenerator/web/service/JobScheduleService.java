package com.datagenerator.web.service;

import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.ScheduleDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.web.dto.JobScheduleRequest;
import com.datagenerator.web.dto.JobScheduleResponse;
import com.datagenerator.web.exception.ReadOnlyScheduleException;
import com.datagenerator.web.storage.JobScheduleRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
public class JobScheduleService {

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final JobScheduleRepository scheduleRepository;

    public JobScheduleService(ConfigPathResolver pathResolver, JobScheduleRepository scheduleRepository) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
        this.scheduleRepository = scheduleRepository;
    }

    public JobScheduleResponse resolveSchedule(String configPath, boolean builtin) {
        if (builtin) {
            JobDefinition job = configLoader.loadJob(configPath);
            Optional<ScheduleDefinition> schedule = job.getSchedule();
            if (schedule.isEmpty()) {
                return toResponse(false, null, false);
            }
            ScheduleDefinition definition = schedule.get();
            return toResponse(definition.isEnabled(), normalizeCron(definition.getCron()), false);
        }

        Optional<JobScheduleRepository.JobScheduleRecord> record =
                scheduleRepository.findByConfigPath(configPath);
        if (record.isEmpty()) {
            return toResponse(false, null, true);
        }
        JobScheduleRepository.JobScheduleRecord stored = record.get();
        return toResponse(stored.enabled(), normalizeCron(stored.cron()), true);
    }

    public JobScheduleRequest validateAndNormalize(JobScheduleRequest request) {
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

        JobScheduleRequest normalized = new JobScheduleRequest();
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

    public JobScheduleResponse saveSchedule(String configPath, JobScheduleRequest request) {
        if (isBuiltin(configPath)) {
            throw new ReadOnlyScheduleException(configPath);
        }
        JobScheduleRequest normalized = validateAndNormalize(request);
        scheduleRepository.upsert(
                configPath,
                normalized.isEnabled(),
                normalized.getCron(),
                Instant.now().toString());
        return toResponse(normalized.isEnabled(), normalized.getCron(), true);
    }

    private JobScheduleResponse toResponse(boolean enabled, String cron, boolean editable) {
        String nextRunAt = enabled ? computeNextRunAt(cron) : null;
        return new JobScheduleResponse(enabled, cron, editable, nextRunAt);
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
