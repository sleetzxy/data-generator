package com.datagenerator.web.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSchemaInitializerTest {

    @Test
    void initialize_createsTasksTableWithoutTaskSchedules_idempotent() {
        JdbcTemplate jdbc = SqliteTestSupport.createJdbcTemplate();
        SqliteSchemaInitializer.initialize(jdbc);
        SqliteSchemaInitializer.initialize(jdbc);

        assertThat(tableExists(jdbc, "tasks")).isTrue();
        assertThat(tableExists(jdbc, "task_schedules")).isFalse();
        assertThat(columnExists(jdbc, "task_runs", "trigger_source")).isTrue();

        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(tasks)");
        assertThat(columns).hasSize(7);
        assertThat(columns).extracting(row -> row.get("name"))
                .containsExactlyInAnyOrder(
                        "id", "file_name", "display_name", "schedule_enabled",
                        "schedule_cron", "created_at", "updated_at");
    }

    @Test
    void initialize_createsIndexes_idempotent() {
        JdbcTemplate jdbc = SqliteTestSupport.createJdbcTemplate();
        SqliteSchemaInitializer.initialize(jdbc);
        SqliteSchemaInitializer.initialize(jdbc);

        List<Map<String, Object>> runIndexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='task_runs'");
        assertThat(runIndexes).extracting(row -> row.get("name"))
                .contains("idx_task_runs_submitted_at", "idx_task_runs_status_submitted_at");

        List<Map<String, Object>> taskIndexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='tasks'");
        assertThat(taskIndexes).extracting(row -> row.get("name"))
                .contains("idx_tasks_created_at");
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
