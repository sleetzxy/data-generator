package com.datagenerator.web.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 任务主表（tasks）仓储：任务元数据与调度字段的持久化 */
@Repository
public class TaskRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, file_name, display_name, schedule_enabled, schedule_cron, created_at, updated_at
            FROM tasks
            """;

    private final JdbcTemplate jdbcTemplate;

    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TaskRecord(
            String id,
            String fileName,
            String displayName,
            boolean scheduleEnabled,
            String scheduleCron,
            String createdAt,
            String updatedAt) {
    }

    public void insert(TaskRecord task) {
        jdbcTemplate.update("""
                INSERT INTO tasks (id, file_name, display_name, schedule_enabled, schedule_cron, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(),
                task.fileName(),
                task.displayName(),
                task.scheduleEnabled() ? 1 : 0,
                task.scheduleCron(),
                task.createdAt(),
                task.updatedAt());
    }

    public void update(String fileName, String displayName, String updatedAt) {
        jdbcTemplate.update("""
                UPDATE tasks SET display_name = ?, updated_at = ? WHERE file_name = ?
                """,
                displayName, updatedAt, fileName);
    }

    public void updateSchedule(String fileName, boolean enabled, String cron, String updatedAt) {
        jdbcTemplate.update("""
                UPDATE tasks SET schedule_enabled = ?, schedule_cron = ?, updated_at = ? WHERE file_name = ?
                """,
                enabled ? 1 : 0, cron, updatedAt, fileName);
    }

    public Optional<TaskRecord> findByFileName(String fileName) {
        List<TaskRecord> results = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE file_name = ?", this::mapRow, fileName);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<TaskRecord> findById(String id) {
        List<TaskRecord> results = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = ?", this::mapRow, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** 按文件名批量解析任务显示名（file_name → display_name），任务不存在的键无映射 */
    public Map<String, String> findDisplayNames(Collection<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", fileNames.stream().map(name -> "?").toList());
        List<TaskRecord> records = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE file_name IN (" + placeholders + ")",
                this::mapRow,
                fileNames.toArray());
        Map<String, String> names = new HashMap<>();
        for (TaskRecord record : records) {
            names.put(record.fileName(), record.displayName());
        }
        return names;
    }

    public boolean existsByFileName(String fileName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE file_name = ?", Integer.class, fileName);
        return count != null && count > 0;
    }

    public List<TaskRecord> listPage(int offset, int limit, String nameKeyword) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS);
        List<Object> args = new ArrayList<>();
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            sql.append(" WHERE display_name LIKE ?");
            args.add("%" + nameKeyword.trim() + "%");
        }
        sql.append(" ORDER BY created_at DESC, file_name DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    public long count(String nameKeyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM tasks");
        List<Object> args = new ArrayList<>();
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            sql.append(" WHERE display_name LIKE ?");
            args.add("%" + nameKeyword.trim() + "%");
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 全部启用且配置了 cron 的任务，供启动时注册调度 */
    public List<TaskRecord> findAllEnabledSchedules() {
        String sql = SELECT_COLUMNS
                + " WHERE schedule_enabled = 1 AND schedule_cron IS NOT NULL"
                + " ORDER BY file_name";
        return jdbcTemplate.query(sql, this::mapRow);
    }

    public void deleteByFileName(String fileName) {
        jdbcTemplate.update("DELETE FROM tasks WHERE file_name = ?", fileName);
    }

    private TaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRecord(
                rs.getString("id"),
                rs.getString("file_name"),
                rs.getString("display_name"),
                rs.getInt("schedule_enabled") != 0,
                rs.getString("schedule_cron"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
