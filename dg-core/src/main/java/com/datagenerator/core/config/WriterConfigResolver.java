package com.datagenerator.core.config;

import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.spi.model.WriterConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves job/table writer configuration into one or more writer maps.
 * {@code writer} is single-write; {@code writers} is multi-write.
 */
public final class WriterConfigResolver {

    private WriterConfigResolver() {
    }

    public static List<Map<String, Object>> fromRuntimeOverride(Map<String, Object> writerConfigMap) {
        if (writerConfigMap == null || writerConfigMap.isEmpty()) {
            return List.of();
        }
        validateNotBothWriterAndWriters(writerConfigMap, "runtime writer override");
        List<Map<String, Object>> writers = extractWritersList(writerConfigMap.get("writers"));
        if (!writers.isEmpty()) {
            return writers;
        }
        return List.of(new HashMap<>(writerConfigMap));
    }

    public static List<Map<String, Object>> fromRuntimeOverride(List<Map<String, Object>> requestWriters) {
        if (requestWriters != null && !requestWriters.isEmpty()) {
            for (int index = 0; index < requestWriters.size(); index++) {
                validateWriterEntry(requestWriters.get(index), "request writers[" + index + "]");
            }
            return copyWriterMaps(requestWriters);
        }
        return List.of();
    }

    public static List<Map<String, Object>> resolveDefaultWriters(
            JobDefinition job, List<Map<String, Object>> runtimeWriters) {
        if (!job.getWriters().isEmpty()) {
            return copyWriterMaps(job.getWriters());
        }
        if (!job.getWriter().isEmpty()) {
            return List.of(new HashMap<>(job.getWriter()));
        }
        return copyWriterMaps(runtimeWriters);
    }

    public static List<Map<String, Object>> resolveTableWriters(
            TableTask tableTask, List<Map<String, Object>> defaultWriters) {
        if (!tableTask.getWriters().isEmpty()) {
            return copyWriterMaps(tableTask.getWriters());
        }
        if (!tableTask.getWriter().isEmpty()) {
            return List.of(new HashMap<>(tableTask.getWriter()));
        }
        return copyWriterMaps(defaultWriters);
    }

    public static void validateWriterMapsConfigured(String tableName, List<Map<String, Object>> writerMaps) {
        if (writerMaps == null || writerMaps.isEmpty()) {
            throw new IllegalArgumentException(
                    "表 '" + tableName + "' 缺少 writer 配置，请在表级或 job 级指定 writer 或 writers");
        }
        for (int index = 0; index < writerMaps.size(); index++) {
            validateWriterEntry(writerMaps.get(index), "表 '" + tableName + "' writers[" + index + "]");
        }
    }

    public static void validateJobWriters(JobDefinition job) {
        validateScopeWriters(job.getWriter(), job.getWriters(), "job '" + job.getId() + "'");
        for (TableTask table : job.getTables()) {
            validateScopeWriters(
                    table.getWriter(),
                    table.getWriters(),
                    "表 '" + table.getName() + "'");
        }
    }

    public static String writerKey(List<Map<String, Object>> writerMaps, ConnectionRegistry connectionRegistry) {
        return writerMaps.stream()
                .map(map -> writerKey(connectionRegistry.resolveWriter(map)))
                .sorted()
                .collect(Collectors.joining(";;"));
    }

    public static String writerKey(WriterConfig config) {
        return String.join(
                "|",
                String.valueOf(config.type()),
                String.valueOf(config.connection()),
                String.valueOf(config.table()),
                String.valueOf(config.mode()),
                String.valueOf(config.path()),
                String.valueOf(config.url()));
    }

    private static void validateScopeWriters(
            Map<String, Object> writer, List<Map<String, Object>> writers, String scope) {
        if (!writer.isEmpty() && !writers.isEmpty()) {
            throw new ConfigLoadException(scope + " 不能同时配置 writer 与 writers");
        }
        if (!writers.isEmpty()) {
            for (int index = 0; index < writers.size(); index++) {
                validateWriterEntry(writers.get(index), scope + " writers[" + index + "]");
            }
        }
    }

    private static void validateNotBothWriterAndWriters(Map<String, Object> writerMap, String scope) {
        if (writerMap.containsKey("writers") && writerMap.get("type") != null) {
            throw new ConfigLoadException(scope + " 不能同时包含 writer 字段与 writers 列表");
        }
    }

    private static void validateWriterEntry(Map<String, Object> writerMap, String scope) {
        if (writerMap == null || writerMap.isEmpty()) {
            throw new IllegalArgumentException(scope + " 不能为空");
        }
        Object type = writerMap.get("type");
        if (type == null || String.valueOf(type).isBlank()) {
            throw new IllegalArgumentException(scope + " 缺少 type");
        }
    }

    private static List<Map<String, Object>> extractWritersList(Object writersValue) {
        if (writersValue == null) {
            return List.of();
        }
        if (!(writersValue instanceof List<?> rawList)) {
            throw new ConfigLoadException("Expected writers list but got: " + writersValue.getClass().getSimpleName());
        }
        if (rawList.isEmpty()) {
            throw new ConfigLoadException("writers 不能为空列表");
        }
        List<Map<String, Object>> writers = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            writers.add(asWriterMap(item));
        }
        return copyWriterMaps(writers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asWriterMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new ConfigLoadException("Expected writer map but got: " + value.getClass().getSimpleName());
        }
        Map<String, Object> result = new HashMap<>();
        rawMap.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
        return result;
    }

    private static List<Map<String, Object>> copyWriterMaps(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copies = new ArrayList<>(source.size());
        for (Map<String, Object> writerMap : source) {
            copies.add(new HashMap<>(writerMap));
        }
        return copies;
    }
}
