package com.datagenerator.web.storage;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

public final class SqliteSchemaInitializer {

    private SqliteSchemaInitializer() {
    }

    public static void initialize(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS task_runs (
                    run_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    config_path TEXT,
                    submitted_at TEXT NOT NULL,
                    duration TEXT,
                    error_message TEXT,
                    total_tables INTEGER NOT NULL DEFAULT 0,
                    completed_tables INTEGER NOT NULL DEFAULT 0,
                    total_rows INTEGER NOT NULL DEFAULT 0,
                    written_rows INTEGER NOT NULL DEFAULT 0,
                    failed_rows INTEGER NOT NULL DEFAULT 0,
                    details_json TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_task_runs_submitted_at
                ON task_runs(submitted_at)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_task_runs_status_submitted_at
                ON task_runs(status, submitted_at DESC)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS task_schedules (
                    config_path TEXT PRIMARY KEY,
                    enabled INTEGER NOT NULL DEFAULT 0,
                    cron TEXT,
                    updated_at TEXT NOT NULL
                )
                """);
        ensureColumn(jdbcTemplate, "task_runs", "trigger_source", "TEXT");
        ensureColumn(jdbcTemplate, "task_schedules", "created_at", "TEXT");
    }

    private static boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                Integer.class,
                tableName);
        return count != null && count > 0;
    }

    private static void ensureColumn(
            JdbcTemplate jdbcTemplate, String table, String column, String type) {
        if (!tableExists(jdbcTemplate, table)) {
            return;
        }
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
        boolean exists = columns.stream().anyMatch(row -> column.equals(row.get("name")));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }
}
