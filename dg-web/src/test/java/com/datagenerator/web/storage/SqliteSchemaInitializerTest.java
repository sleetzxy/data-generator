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
    void initialize_createsIndexes_idempotent() {
        JdbcTemplate jdbc = SqliteTestSupport.createJdbcTemplate();
        SqliteSchemaInitializer.initialize(jdbc);
        SqliteSchemaInitializer.initialize(jdbc);

        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='task_runs'");
        assertThat(indexes).extracting(row -> row.get("name"))
                .contains("idx_task_runs_submitted_at", "idx_task_runs_status_submitted_at");
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
