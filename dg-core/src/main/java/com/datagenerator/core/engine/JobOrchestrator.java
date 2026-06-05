package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
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
import java.util.List;
import java.util.Map;

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
        List<TableTask> sortedTables = DagSorter.sort(new ArrayList<>(job.getTables()));
        Map<String, List<DataRow>> upstreamTables = new HashMap<>();
        List<TableResult> details = new ArrayList<>();
        long totalRows = 0;
        long writtenRows = 0;
        long failedRows = 0;

        Map<String, Object> defaultWriterConfig = resolveDefaultWriter(job, writerConfigMap);
        DataWriter writer = null;
        String activeWriterKey = null;

        try {
            for (TableTask tableTask : sortedTables) {
                Map<String, Object> tableWriterConfig = resolveTableWriter(tableTask, defaultWriterConfig);
                WriterConfig resolvedWriter = connectionRegistry.resolveWriter(tableWriterConfig);
                String writerKey = writerKey(resolvedWriter);
                if (writer == null || !writerKey.equals(activeWriterKey)) {
                    if (writer != null) {
                        writer.flush();
                        writer.close();
                    }
                    writer = pluginRegistry.getWriter(resolvedWriter.type());
                    writer.init(resolvedWriter);
                    activeWriterKey = writerKey;
                }

                SchemaDefinition schema = resolveSchema(tableTask);
                List<com.datagenerator.core.schema.ConstraintDefinition> constraints =
                        constraintLoader.load(schema, job, tableTask);

                TableGenerationResult result = tableGenerator.generate(
                        schema,
                        tableTask.getCount(),
                        constraints,
                        pluginRegistry.getConstraintRegistry(),
                        upstreamTables,
                        writer,
                        options);

                upstreamTables.put(tableTask.getName(), result.generatedRows());

                totalRows += tableTask.getCount();
                writtenRows += result.writtenRows();
                failedRows += result.failedRows();

                String status = result.failedRows() > 0 ? "partial" : "ok";
                details.add(new TableResult(tableTask.getName(), result.writtenRows(), result.failedRows(), status));
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        return new JobResult(totalRows, writtenRows, failedRows, details);
    }

    private static Map<String, Object> resolveDefaultWriter(JobDefinition job, Map<String, Object> writerConfigMap) {
        Map<String, Object> merged = new HashMap<>();
        if (writerConfigMap != null) {
            merged.putAll(writerConfigMap);
        }
        if (!job.getWriter().isEmpty()) {
            merged.putAll(job.getWriter());
        }
        return merged;
    }

    private static Map<String, Object> resolveTableWriter(TableTask tableTask, Map<String, Object> defaultWriterConfig) {
        Map<String, Object> merged = new HashMap<>(defaultWriterConfig);
        if (!tableTask.getWriter().isEmpty()) {
            merged.putAll(tableTask.getWriter());
        }
        if (merged.isEmpty() || merged.get("type") == null || String.valueOf(merged.get("type")).isBlank()) {
            throw new IllegalArgumentException(
                    "Table '" + tableTask.getName() + "' requires writer configuration");
        }
        return merged;
    }

    private static String writerKey(WriterConfig config) {
        return String.join(
                "|",
                String.valueOf(config.type()),
                String.valueOf(config.connection()),
                String.valueOf(config.path()),
                String.valueOf(config.url()));
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
}
