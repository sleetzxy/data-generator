package com.datagenerator.core.config;

import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.TableTask;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriterConfigResolverTest {

    @Test
    void resolveDefaultWriters_jobWritersOverrideRuntime() {
        JobDefinition job = new JobDefinition();
        job.setWriters(List.of(
                Map.of("type", "postgresql", "connection", "pg"),
                Map.of("type", "clickhouse", "connection", "ck")));

        List<Map<String, Object>> resolved = WriterConfigResolver.resolveDefaultWriters(
                job, List.of(Map.of("type", "csv", "connection", "local")));

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
    void validateJobWriters_bothWriterAndWritersAtJobLevel_throws() {
        JobDefinition job = new JobDefinition();
        job.setId("demo");
        job.setWriter(Map.of("type", "postgresql"));
        job.setWriters(List.of(Map.of("type", "clickhouse")));

        assertThatThrownBy(() -> WriterConfigResolver.validateJobWriters(job))
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
