package com.datagenerator.web.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JobScheduleRepository {

    private final JdbcTemplate jdbcTemplate;

    public JobScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record JobScheduleRecord(String configPath, boolean enabled, String cron, String updatedAt) {
    }

    public void upsert(String configPath, boolean enabled, String cron, String updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO job_schedules (config_path, enabled, cron, updated_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(config_path) DO UPDATE SET
                    enabled = excluded.enabled,
                    cron = excluded.cron,
                    updated_at = excluded.updated_at
                """,
                configPath,
                enabled ? 1 : 0,
                cron,
                updatedAt,
                updatedAt);
    }

    /** 记录任务创建时间；已有行时仅补填缺失的 created_at，不覆盖已有值。 */
    public void ensureCreatedAt(String configPath, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO job_schedules (config_path, enabled, cron, updated_at, created_at)
                VALUES (?, 0, NULL, ?, ?)
                ON CONFLICT(config_path) DO UPDATE SET
                    created_at = COALESCE(job_schedules.created_at, excluded.created_at)
                """,
                configPath,
                createdAt,
                createdAt);
    }

    public Optional<String> findCreatedAt(String configPath) {
        List<String> results = jdbcTemplate.query(
                "SELECT created_at FROM job_schedules WHERE config_path = ?",
                (rs, rowNum) -> rs.getString("created_at"),
                configPath);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        String createdAt = results.get(0);
        return createdAt == null || createdAt.isBlank() ? Optional.empty() : Optional.of(createdAt);
    }

    public Optional<JobScheduleRecord> findByConfigPath(String configPath) {
        List<JobScheduleRecord> results = jdbcTemplate.query("""
                SELECT config_path, enabled, cron, updated_at
                FROM job_schedules WHERE config_path = ?
                """, this::mapRow, configPath);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<JobScheduleRecord> findAll() {
        return jdbcTemplate.query("""
                SELECT config_path, enabled, cron, updated_at
                FROM job_schedules ORDER BY config_path
                """, this::mapRow);
    }

    public void deleteByConfigPath(String configPath) {
        jdbcTemplate.update("DELETE FROM job_schedules WHERE config_path = ?", configPath);
    }

    public void deleteOrphans(Set<String> validConfigPaths) {
        if (validConfigPaths.isEmpty()) {
            jdbcTemplate.update("DELETE FROM job_schedules");
            return;
        }
        String placeholders = String.join(",", validConfigPaths.stream().map(path -> "?").toList());
        jdbcTemplate.update(
                "DELETE FROM job_schedules WHERE config_path NOT IN (" + placeholders + ")",
                validConfigPaths.toArray());
    }

    private JobScheduleRecord mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobScheduleRecord(
                rs.getString("config_path"),
                rs.getInt("enabled") != 0,
                rs.getString("cron"),
                rs.getString("updated_at"));
    }
}
