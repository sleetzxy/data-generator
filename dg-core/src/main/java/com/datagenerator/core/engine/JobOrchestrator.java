package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
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

        Map<String, Object> baseWriterConfig = writerConfigMap == null ? Map.of() : writerConfigMap;
        String writerType = String.valueOf(baseWriterConfig.get("type"));
        DataWriter writer = pluginRegistry.getWriter(writerType);
        WriterConfig resolvedWriter = connectionRegistry.resolveWriter(baseWriterConfig);
        writer.init(resolvedWriter);

        try {
            for (TableTask tableTask : sortedTables) {
                SchemaDefinition schema = configLoader.loadSchema(tableTask.getSchema());
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
            writer.close();
        }

        return new JobResult(totalRows, writtenRows, failedRows, details);
    }
}
