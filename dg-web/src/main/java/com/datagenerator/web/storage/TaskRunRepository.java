package com.datagenerator.web.storage;

import com.datagenerator.web.dto.ConfigVolumeStat;
import com.datagenerator.web.dto.DailyRunStat;
import com.datagenerator.web.dto.TaskRunListFilter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TaskRunRepository {

    private static final TypeReference<List<TableDetail>> TABLE_DETAIL_LIST = new TypeReference<>() {};

    private static final String SELECT_COLUMNS = """
            SELECT run_id, status, config_path, submitted_at, duration, error_message,
                   total_tables, completed_tables, total_rows, written_rows, failed_rows,
                   details_json, trigger_source
            FROM task_runs
            """;

    /** 每个 config_path 取 submitted_at 最新的一条（窗口函数分组排序） */
    private static final String LATEST_RUN_BY_CONFIG_SQL = """
            SELECT run_id, status, config_path, submitted_at, duration, error_message,
                   total_tables, completed_tables, total_rows, written_rows, failed_rows,
                   details_json, trigger_source
            FROM (
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source,
                       ROW_NUMBER() OVER (PARTITION BY config_path ORDER BY submitted_at DESC) AS rn
                FROM task_runs
            )
            WHERE rn = 1
            """;

    /** 每个 config_path 取活跃（等待中/运行中）的最新一条 */
    private static final String ACTIVE_RUN_BY_CONFIG_SQL = """
            SELECT run_id, status, config_path, submitted_at, duration, error_message,
                   total_tables, completed_tables, total_rows, written_rows, failed_rows,
                   details_json, trigger_source
            FROM (
                SELECT run_id, status, config_path, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows,
                       details_json, trigger_source,
                       ROW_NUMBER() OVER (PARTITION BY config_path ORDER BY submitted_at DESC) AS rn
                FROM task_runs
                WHERE status IN ('PENDING', 'RUNNING')
            )
            WHERE rn = 1
            """;

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
        List<TaskRunResponse> results = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE run_id = ?", this::mapRow, runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<TaskRunResponse> listAll() {
        return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY submitted_at DESC", this::mapRow);
    }

    public List<TaskRunResponse> listPage(int offset, int limit, TaskRunListFilter filter) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS);
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, filter);
        sql.append(" ORDER BY submitted_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    public long countAll(TaskRunListFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM task_runs");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, filter);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public List<TaskRunResponse> findByStatusIn(List<TaskRunStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(status -> "?").toList());
        Object[] args = statuses.stream().map(TaskRunStatus::name).toArray();
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE status IN (" + placeholders + ")", this::mapRow, args);
    }

    public List<TaskRunResponse> findRunningByConfigPath(String configPath) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE config_path = ? AND status = 'RUNNING'",
                this::mapRow, configPath);
    }

    /** 按状态统计运行次数（未出现的状态不包含在结果中） */
    public Map<String, Long> countByStatus() {
        return jdbcTemplate.query("SELECT status, COUNT(*) AS cnt FROM task_runs GROUP BY status", rs -> {
            Map<String, Long> counts = new HashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("status"), rs.getLong("cnt"));
            }
            return counts;
        });
    }

    public long sumWrittenRows() {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(written_rows), 0) FROM task_runs", Long.class);
        return sum == null ? 0L : sum;
    }

    /** 按配置路径聚合写入行数，取前 limit 名（按累计写入行数降序）；displayName 由服务层按任务主表补全 */
    public List<ConfigVolumeStat> topWrittenByConfigPath(int limit) {
        return jdbcTemplate.query("""
                SELECT COALESCE(config_path, '') AS config_path,
                       COUNT(*) AS run_count,
                       COALESCE(SUM(written_rows), 0) AS written_rows
                FROM task_runs
                GROUP BY config_path
                ORDER BY written_rows DESC, config_path ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new ConfigVolumeStat(
                        rs.getString("config_path"),
                        null,
                        rs.getLong("run_count"),
                        rs.getLong("written_rows")),
                limit);
    }

    /** 自 fromDate（yyyy-MM-dd，含）起的每日运行统计，按日期升序 */
    public List<DailyRunStat> dailyRunStats(String fromDate) {
        return jdbcTemplate.query("""
                SELECT substr(submitted_at, 1, 10) AS day,
                       COUNT(*) AS run_count,
                       COALESCE(SUM(written_rows), 0) AS written_rows
                FROM task_runs
                WHERE substr(submitted_at, 1, 10) >= ?
                GROUP BY day
                ORDER BY day
                """,
                (rs, rowNum) -> new DailyRunStat(
                        rs.getString("day"),
                        rs.getLong("run_count"),
                        rs.getLong("written_rows")),
                fromDate);
    }

    /** 每个配置路径的最新一次运行 */
    public List<TaskRunResponse> latestRunsByConfigPath() {
        return jdbcTemplate.query(LATEST_RUN_BY_CONFIG_SQL, this::mapRow);
    }

    /** 每个配置路径的活跃运行（等待中/运行中，最新一条） */
    public List<TaskRunResponse> activeRunsByConfigPath() {
        return jdbcTemplate.query(ACTIVE_RUN_BY_CONFIG_SQL, this::mapRow);
    }

    public void delete(String runId) {
        jdbcTemplate.update("DELETE FROM task_runs WHERE run_id = ?", runId);
    }

    /** 将查询条件拼接到 SQL 与参数列表，filter 为 null 时不过滤 */
    private static void appendFilter(StringBuilder sql, List<Object> args, TaskRunListFilter filter) {
        if (filter == null) {
            return;
        }
        List<String> conditions = new ArrayList<>();
        if (filter.status() != null && !filter.status().isBlank()) {
            // 支持逗号分隔的多个状态，如 "RUNNING,PENDING"
            List<String> statuses = List.of(filter.status().split(","));
            String placeholders = String.join(",", statuses.stream().map(status -> "?").toList());
            conditions.add("status IN (" + placeholders + ")");
            args.addAll(statuses);
        }
        if (filter.configPath() != null && !filter.configPath().isBlank()) {
            conditions.add("config_path = ?");
            args.add(filter.configPath());
        }
        // 时间范围比较截断到秒（前 19 位）：既有数据存在纳秒为 0 时省略小数部分的格式
        // （如 ...T06:00:00Z），与带小数的边界值（如 ...T06:00:00.500Z）做完整字符串
        // 字典序比较会错序（'Z' > '.'），截断到秒后比较结果稳定
        if (filter.from() != null && !filter.from().isBlank()) {
            conditions.add("substr(submitted_at, 1, 19) >= substr(?, 1, 19)");
            args.add(filter.from());
        }
        if (filter.to() != null && !filter.to().isBlank()) {
            conditions.add("substr(submitted_at, 1, 19) <= substr(?, 1, 19)");
            args.add(filter.to());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
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
