package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.config.WriterConfigResolver;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.TableSchema;
import com.datagenerator.core.model.TableTask;
import com.datagenerator.core.model.YamlConfigLoader;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TaskRunOrchestrator {

    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final TableGenerator tableGenerator;
    private final PluginRegistry pluginRegistry;
    private final ConnectionRegistry connectionRegistry;

    public TaskRunOrchestrator(
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            TableGenerator tableGenerator,
            PluginRegistry pluginRegistry,
            ConnectionRegistry connectionRegistry) {
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.tableGenerator = tableGenerator;
        this.pluginRegistry = pluginRegistry;
        this.connectionRegistry = connectionRegistry;
    }

    public TaskRunResult run(TaskConfig taskConfig, Map<String, Object> writerConfigMap, GenerationOptions options) {
        return run(taskConfig, writerConfigMap, options, TaskRunExecutionListener.NOOP);
    }

    public TaskRunResult run(
            TaskConfig taskConfig,
            Map<String, Object> writerConfigMap,
            GenerationOptions options,
            TaskRunExecutionListener listener) {
        return run(taskConfig, WriterConfigResolver.fromRuntimeOverride(writerConfigMap), options, listener);
    }

    public TaskRunResult run(
            TaskConfig taskConfig,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        return run(taskConfig, runtimeWriters, options, TaskRunExecutionListener.NOOP);
    }

    public TaskRunResult run(
            TaskConfig taskConfig,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options,
            TaskRunExecutionListener listener) {
        List<TableTask> sortedTables = DagSorter.sort(new ArrayList<>(taskConfig.getTables()));
        Map<String, List<DataRow>> upstreamTables = new HashMap<>();
        List<TableResult> details = new ArrayList<>();
        long totalRows = 0;
        long writtenRows = 0;
        long failedRows = 0;
        int totalTables = sortedTables.size();

        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(taskConfig, runtimeWriters);
        ConnectionRegistry effectiveRegistry = connectionRegistry.withOverlay(taskConfig.getConnections());
        DataWriter writer = null;
        String activeWriterKey = null;
        SeedRowSnapshotStore seedRowSnapshots = new SeedRowSnapshotStore();

        try {
            for (int tableIndex = 0; tableIndex < sortedTables.size(); tableIndex++) {
                CancellationChecks.throwIfCancelled(options.cancellationChecker());
                TableTask tableTask = sortedTables.get(tableIndex);
                listener.onTableStarted(tableTask.getName(), tableIndex, totalTables, tableTask.getCount());

                List<Map<String, Object>> tableWriterConfigs =
                        WriterConfigResolver.resolveTableWriters(tableTask, defaultWriters);
                WriterConfigResolver.validateWriterMapsConfigured(tableTask.getName(), tableWriterConfigs);
                String writerKey = WriterConfigResolver.writerKey(tableWriterConfigs, effectiveRegistry);
                if (writer == null || !writerKey.equals(activeWriterKey)) {
                    if (writer != null) {
                        writer.flush();
                        writer.close();
                    }
                    writer = createWriter(tableWriterConfigs, effectiveRegistry);
                    activeWriterKey = writerKey;
                }

                TableSchema schema = resolveSchema(tableTask);
                List<com.datagenerator.core.model.ConstraintDefinition> constraints =
                        constraintLoader.load(schema, taskConfig, tableTask);

                long runWrittenBeforeTable = writtenRows;
                long runFailedBeforeTable = failedRows;
                TaskRunExecutionListener runListener = listener;
                BatchWrittenCallback batchCallback = (tableName, batchWritten, batchFailed, tableWrittenRows, tableFailedRows) ->
                        runListener.onBatchWritten(
                                tableName,
                                batchWritten,
                                batchFailed,
                                tableWrittenRows,
                                tableFailedRows,
                                runWrittenBeforeTable + tableWrittenRows,
                                runFailedBeforeTable + tableFailedRows);

                TableGenerationResult result = tableGenerator.generate(
                        schema,
                        tableTask.getCount(),
                        constraints,
                        pluginRegistry.getConstraintRegistry(),
                        upstreamTables,
                        writer,
                        taskConfig.getSeeds(),
                        options,
                        batchCallback,
                        seedRowSnapshots,
                        effectiveRegistry);

                List<DataRow> upstreamRows = result.generatedRows();
                Set<String> requiredFields = collectUpstreamFields(
                        tableTask.getName(), sortedTables, tableIndex, taskConfig);
                if (!requiredFields.isEmpty()) {
                    upstreamRows = UpstreamFieldCollector.slimRows(upstreamRows, requiredFields);
                }
                upstreamTables.put(tableTask.getName(), upstreamRows);

                totalRows += tableTask.getCount();
                writtenRows += result.writtenRows();
                failedRows += result.failedRows();

                String status = result.failedRows() > 0 ? "partial" : "ok";
                details.add(new TableResult(tableTask.getName(), result.writtenRows(), result.failedRows(), status));
                listener.onTableCompleted(
                        tableTask.getName(),
                        result.writtenRows(),
                        result.failedRows(),
                        tableIndex + 1,
                        totalTables,
                        writtenRows,
                        failedRows);
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        return new TaskRunResult(totalRows, writtenRows, failedRows, details);
    }

    private DataWriter createWriter(List<Map<String, Object>> writerMaps, ConnectionRegistry registry) {
        if (writerMaps.size() == 1) {
            WriterConfig resolvedWriter = registry.resolveWriter(writerMaps.getFirst());
            DataWriter delegate = pluginRegistry.getWriter(resolvedWriter.type());
            delegate.init(resolvedWriter);
            return delegate;
        }
        List<DataWriter> delegates = new ArrayList<>(writerMaps.size());
        for (Map<String, Object> writerMap : writerMaps) {
            WriterConfig resolvedWriter = registry.resolveWriter(writerMap);
            DataWriter delegate = pluginRegistry.getWriter(resolvedWriter.type());
            delegate.init(resolvedWriter);
            delegates.add(delegate);
        }
        return new CompositeWriter(delegates);
    }

    private TableSchema resolveSchema(TableTask tableTask) {
        if (tableTask.getSchemaDefinition() != null) {
            return tableTask.getSchemaDefinition();
        }
        if (tableTask.getSchema() == null || tableTask.getSchema().isBlank()) {
            throw new ConfigLoadException("Table '" + tableTask.getName() + "' has no schema defined");
        }
        return configLoader.loadSchema(tableTask.getSchema());
    }

    private Set<String> collectUpstreamFields(
            String upstreamTableName,
            List<TableTask> sortedTables,
            int currentTableIndex,
            TaskConfig taskConfig) {
        Set<String> fields = new HashSet<>();
        for (int i = currentTableIndex + 1; i < sortedTables.size(); i++) {
            TableTask downstream = sortedTables.get(i);
            if (!downstream.getDependsOn().contains(upstreamTableName)) {
                continue;
            }
            TableSchema downstreamSchema = resolveSchema(downstream);
            List<com.datagenerator.core.model.ConstraintDefinition> downstreamConstraints =
                    constraintLoader.load(downstreamSchema, taskConfig, downstream);
            fields.addAll(UpstreamFieldCollector.collectRequiredFields(
                    upstreamTableName, downstreamSchema, downstreamConstraints));
        }
        return fields;
    }
}
