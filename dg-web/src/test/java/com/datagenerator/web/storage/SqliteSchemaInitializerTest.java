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
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='job_schedules'",
                Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(columnExists(jdbc, "jobs", "trigger_source")).isTrue();
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        return columns.stream().anyMatch(row -> column.equals(row.get("name")));
    }
}
