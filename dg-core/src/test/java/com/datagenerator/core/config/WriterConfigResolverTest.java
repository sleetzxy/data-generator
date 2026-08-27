package com.datagenerator.core.config;

import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.TableTask;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriterConfigResolverTest {

    @Test
    void resolveDefaultWriters_jobWritersOverrideRuntime() {
        TaskConfig taskConfig = new TaskConfig();
        taskConfig.setWriters(List.of(
                Map.of("type", "postgresql", "connection", "pg"),
                Map.of("type", "clickhouse", "connection", "ck")));

        List<Map<String, Object>> resolved = WriterConfigResolver.resolveDefaultWriters(
                taskConfig, List.of(Map.of("type", "csv", "connection", "local")));

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0)).containsEntry("type", "postgresql");
        assertThat(resolved.get(1)).containsEntry("type", "clickhouse");
    }

    @Test
    void resolveTableWriters_tableWritersOverrideDefault() {
        TableTask table = new TableTask();
        table.setName("orders");
        table.setWriters(List.of(Map.of("type", "clickhouse", "connection", "ck")));

        List<Map<String, Object>> resolved = WriterConfigResolver.resolveTableWriters(
                table,
                List.of(Map.of("type", "postgresql", "connection", "pg")));

        assertThat(resolved).containsExactly(Map.of("type", "clickhouse", "connection", "ck"));
    }

    @Test
    void validateTaskConfigWriters_bothWriterAndWritersAtJobLevel_throws() {
        TaskConfig taskConfig = new TaskConfig();
        taskConfig.setId("demo");
        taskConfig.setWriter(Map.of("type", "postgresql"));
        taskConfig.setWriters(List.of(Map.of("type", "clickhouse")));

        assertThatThrownBy(() -> WriterConfigResolver.validateTaskConfigWriters(taskConfig))
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("writer 与 writers");
    }

    @Test
    void fromRuntimeOverride_mapWithWritersList_returnsEntries() {
        Map<String, Object> runtime = new HashMap<>();
        runtime.put(
                "writers",
                List.of(
                        Map.of("type", "postgresql", "connection", "pg"),
                        Map.of("type", "clickhouse", "connection", "ck")));

        List<Map<String, Object>> resolved = WriterConfigResolver.fromRuntimeOverride(runtime);

        assertThat(resolved).hasSize(2);
    }
}
