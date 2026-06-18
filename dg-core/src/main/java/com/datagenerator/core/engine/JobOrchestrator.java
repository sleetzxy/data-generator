package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.config.WriterConfigResolver;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JobOrchestrator {

    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final TableGenerator tableGenerator;
    private final PluginRegistry pluginRegistry;
    private final ConnectionRegistry connectionRegistry;

    public JobOrchestrator(
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

    public JobResult run(JobDefinition job, Map<String, Object> writerConfigMap, GenerationOptions options) {
        return run(job, writerConfigMap, options, JobExecutionListener.NOOP);
    }

    public JobResult run(
            JobDefinition job,
            Map<String, Object> writerConfigMap,
            GenerationOptions options,
            JobExecutionListener listener) {
        return run(job, WriterConfigResolver.fromRuntimeOverride(writerConfigMap), options, listener);
    }

    public JobResult run(
            JobDefinition job,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        return run(job, runtimeWriters, options, JobExecutionListener.NOOP);
    }

    public JobResult run(
            JobDefinition job,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options,
            JobExecutionListener listener) {
        List<TableTask> sortedTables = DagSorter.sort(new ArrayList<>(job.getTables()));
        Map<String, List<DataRow>> upstreamTables = new HashMap<>();
        List<TableResult> details = new ArrayList<>();
        long totalRows = 0;
        long writtenRows = 0;
        long failedRows = 0;
        int totalTables = sortedTables.size();

        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(job, runtimeWriters);
        DataWriter writer = null;
        String activeWriterKey = null;
        SeedRowSnapshotStore seedRowSnapshots = new SeedRowSnapshotStore();

        try {
            for (int tableIndex = 0; tableIndex < sortedTables.size(); tableIndex++) {
                TableTask tableTask = sortedTables.get(tableIndex);
                listener.onTableStarted(tableTask.getName(), tableIndex, totalTables, tableTask.getCount());

                List<Map<String, Object>> tableWriterConfigs =
                        WriterConfigResolver.resolveTableWriters(tableTask, defaultWriters);
                WriterConfigResolver.validateWriterMapsConfigured(tableTask.getName(), tableWriterConfigs);
                String writerKey = WriterConfigResolver.writerKey(tableWriterConfigs, connectionRegistry);
                if (writer == null || !writerKey.equals(activeWriterKey)) {
                    if (writer != null) {
                        writer.flush();
                        writer.close();
                    }
                    writer = createWriter(tableWriterConfigs);
                    activeWriterKey = writerKey;
                }

                SchemaDefinition schema = resolveSchema(tableTask);
                List<com.datagenerator.core.schema.ConstraintDefinition> constraints =
                        constraintLoader.load(schema, job, tableTask);

                long jobWrittenBeforeTable = writtenRows;
                long jobFailedBeforeTable = failedRows;
                JobExecutionListener jobListener = listener;
                BatchWrittenCallback batchCallback = (tableName, batchWritten, batchFailed, tableWrittenRows, tableFailedRows) ->
                        jobListener.onBatchWritten(
                                tableName,
                                batchWritten,
                                batchFailed,
                                tableWrittenRows,
                                tableFailedRows,
                                jobWrittenBeforeTable + tableWrittenRows,
                                jobFailedBeforeTable + tableFailedRows);

                TableGenerationResult result = tableGenerator.generate(
                        schema,
                        tableTask.getCount(),
                        constraints,
                        pluginRegistry.getConstraintRegistry(),
                        upstreamTables,
                        writer,
                        job.getSeeds(),
                        options,
                        batchCallback,
                        seedRowSnapshots);

                List<DataRow> upstreamRows = result.generatedRows();
                Set<String> requiredFields = collectUpstreamFields(
                        tableTask.getName(), sortedTables, tableIndex, job);
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

        return new JobResult(totalRows, writtenRows, failedRows, details);
    }

    private DataWriter createWriter(List<Map<String, Object>> writerMaps) {
        if (writerMaps.size() == 1) {
            WriterConfig resolvedWriter = connectionRegistry.resolveWriter(writerMaps.getFirst());
            DataWriter delegate = pluginRegistry.getWriter(resolvedWriter.type());
            delegate.init(resolvedWriter);
            return delegate;
        }
        List<DataWriter> delegates = new ArrayList<>(writerMaps.size());
        for (Map<String, Object> writerMap : writerMaps) {
            WriterConfig resolvedWriter = connectionRegistry.resolveWriter(writerMap);
            DataWriter delegate = pluginRegistry.getWriter(resolvedWriter.type());
            delegate.init(resolvedWriter);
            delegates.add(delegate);
        }
        return new CompositeWriter(delegates);
    }

    private SchemaDefinition resolveSchema(TableTask tableTask) {
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
            JobDefinition job) {
        Set<String> fields = new HashSet<>();
        for (int i = currentTableIndex + 1; i < sortedTables.size(); i++) {
            TableTask downstream = sortedTables.get(i);
            if (!downstream.getDependsOn().contains(upstreamTableName)) {
                continue;
            }
            SchemaDefinition downstreamSchema = resolveSchema(downstream);
            List<com.datagenerator.core.schema.ConstraintDefinition> downstreamConstraints =
                    constraintLoader.load(downstreamSchema, job, downstream);
            fields.addAll(UpstreamFieldCollector.collectRequiredFields(
                    upstreamTableName, downstreamSchema, downstreamConstraints));
        }
        return fields;
    }
}
