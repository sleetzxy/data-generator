package com.datagenerator.web.storage;

import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.dto.TriggerSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class TaskRunRepository {

    private static final TypeReference<List<TableDetail>> TABLE_DETAIL_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(TaskRunResponse taskRun) {
        TaskRunProgress progress = progressOrEmpty(taskRun);
        jdbcTemplate.update("""
                INSERT INTO task_runs (
                    run_id, status, config_path, submitted_at, duration, error_message,
                    total_tables, completed_tables, total_rows, written_rows, failed_rows,
                    details_json, trigger_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskRun.getRunId(),
                taskRun.getStatus().name(),
                taskRun.getConfigPath(),
                taskRun.getSubmittedAt(),
                taskRun.getDuration(),
                taskRun.getErrorMessage(),
                progress.getTotalTables(),
                progress.getCompletedTables(),
                progress.getTotalRows(),
                progress.getWrittenRows(),
                progress.getFailedRows(),
                serializeDetails(taskRun.getDetails()),
                triggerSourceName(taskRun.getTriggerSource()));
    }

    public void update(TaskRunResponse taskRun) {
        TaskRunProgress progress = progressOrEmpty(taskRun);
        jdbcTemplate.update("""
                UPDATE task_runs SET
                    status = ?,
                    config_path = ?,
                    submitted_at = ?,
                    duration = ?,
                    error_message = ?,
                    total_tables = ?,
                    completed_tables = ?,
                    total_rows = ?,
                    written_rows = ?,
                    failed_rows = ?,
                    details_json = ?,
                    trigger_source = ?
                WHERE run_id = ?
                """,
                taskRun.getStatus().name(),
                taskRun.getConfigPath(),
                taskRun.getSubmittedAt(),
                taskRun.getDuration(),
                taskRun.getErrorMessage(),
                progress.getTotalTables(),
                progress.getCompletedTables(),
                progress.getTotalRows(),
                progress.getWrittenRows(),
                progress.getFailedRows(),
                serializeDetails(taskRun.getDetails()),
                triggerSourceName(taskRun.getTriggerSource()),
                taskRun.getRunId());
    }

    public Optional<TaskRunResponse> findById(String runId) {
        List<TaskRunResponse> results = jdbcTemplate.query("""
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source
                FROM task_runs WHERE run_id = ?
                """, this::mapRow, runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<TaskRunResponse> listAll() {
        return jdbcTemplate.query("""
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source
                FROM task_runs ORDER BY submitted_at DESC
                """, this::mapRow);
    }

    public List<TaskRunResponse> listPage(int offset, int limit) {
        return jdbcTemplate.query("""
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source
                FROM task_runs ORDER BY submitted_at DESC LIMIT ? OFFSET ?
                """, this::mapRow, limit, offset);
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_runs", Long.class);
        return count == null ? 0L : count;
    }

    public List<TaskRunResponse> findByStatusIn(List<TaskRunStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(status -> "?").toList());
        Object[] args = statuses.stream().map(TaskRunStatus::name).toArray();
        return jdbcTemplate.query("""
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source
                FROM task_runs WHERE status IN (""" + placeholders + ")", this::mapRow, args);
    }

    public List<TaskRunResponse> findRunningByConfigPath(String configPath) {
        return jdbcTemplate.query("""
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source
                FROM task_runs WHERE config_path = ? AND status = 'RUNNING'
                """, this::mapRow, configPath);
    }

    public void delete(String runId) {
        jdbcTemplate.update("DELETE FROM task_runs WHERE run_id = ?", runId);
    }

    private TaskRunResponse mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        TaskRunProgress progress = new TaskRunProgress(
                rs.getInt("total_tables"),
                rs.getInt("completed_tables"),
                rs.getLong("total_rows"),
                rs.getLong("written_rows"),
                rs.getLong("failed_rows"));
        TaskRunResponse response = new TaskRunResponse(
                rs.getString("run_id"),
                TaskRunStatus.valueOf(rs.getString("status")),
                progress,
                deserializeDetails(rs.getString("details_json")),
                rs.getString("duration"),
                rs.getString("config_path"),
                rs.getString("submitted_at"),
                rs.getString("error_message"),
                null);
        String triggerSource = rs.getString("trigger_source");
        if (triggerSource != null && !triggerSource.isBlank()) {
            response.setTriggerSource(TriggerSource.valueOf(triggerSource));
        }
        return response;
    }

    private static TaskRunProgress progressOrEmpty(TaskRunResponse taskRun) {
        return taskRun.getProgress() == null ? new TaskRunProgress(0, 0, 0, 0, 0) : taskRun.getProgress();
    }

    private static String triggerSourceName(TriggerSource triggerSource) {
        return triggerSource == null ? null : triggerSource.name();
    }

    private String serializeDetails(List<TableDetail> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize task run details", exception);
        }
    }

    private List<TableDetail> deserializeDetails(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, TABLE_DETAIL_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize task run details", exception);
        }
    }
}
