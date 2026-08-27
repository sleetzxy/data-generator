package com.datagenerator.web.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSchemaInitializerTest {

    @Test
    void initialize_addsTriggerSourceColumn_idempotent() {
        JdbcTemplate jdbc = SqliteTestSupport.createJdbcTemplate();
        SqliteSchemaInitializer.initialize(jdbc);
        SqliteSchemaInitializer.initialize(jdbc);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='task_schedules'",
                Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(columnExists(jdbc, "task_runs", "trigger_source")).isTrue();
    }

    @Test
    void initialize_migratesLegacyJobsTable() {
        JdbcTemplate jdbc = SqliteTestSupport.createJdbcTemplate();
        jdbc.execute("""
                CREATE TABLE jobs (
                    job_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    job_config TEXT,
                    submitted_at TEXT NOT NULL,
                    duration TEXT,
                    error_message TEXT,
                    total_tables INTEGER NOT NULL DEFAULT 0,
                    completed_tables INTEGER NOT NULL DEFAULT 0,
                    total_rows INTEGER NOT NULL DEFAULT 0,
                    written_rows INTEGER NOT NULL DEFAULT 0,
                    failed_rows INTEGER NOT NULL DEFAULT 0,
                    details_json TEXT,
                    trigger_source TEXT
                )
                """);
        jdbc.update("""
                INSERT INTO jobs (
                    job_id, status, job_config, submitted_at, duration, error_message,
                    total_tables, completed_tables, total_rows, written_rows, failed_rows,
                    details_json, trigger_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-1",
                "COMPLETED",
                "jobs/demo.yaml",
                "2026-01-01T00:00:00Z",
                "1s",
                null,
                1,
                1,
                10,
                10,
                0,
                null,
                "MANUAL");

        SqliteSchemaInitializer.initialize(jdbc);

        assertThat(tableExists(jdbc, "jobs")).isFalse();
        assertThat(tableExists(jdbc, "task_runs")).isTrue();
        Map<String, Object> row = jdbc.queryForMap("SELECT run_id, config_path FROM task_runs WHERE run_id = ?", "legacy-1");
        assertThat(row.get("run_id")).isEqualTo("legacy-1");
        assertThat(row.get("config_path")).isEqualTo("jobs/demo.yaml");
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        return columns.stream().anyMatch(row -> column.equals(row.get("name")));
    }

    private static boolean tableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                Integer.class,
                tableName);
        return count != null && count > 0;
    }
}
