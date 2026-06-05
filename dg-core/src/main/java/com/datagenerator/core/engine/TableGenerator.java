package com.datagenerator.core.engine;

import com.datagenerator.core.constraint.ConstraintPipeline;
import com.datagenerator.core.constraint.ConstraintValidatorRegistry;
import com.datagenerator.core.generator.GeneratorRegistry;
import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TableGenerator {

    private final GeneratorRegistry generatorRegistry;

    public TableGenerator(GeneratorRegistry generatorRegistry) {
        this.generatorRegistry = generatorRegistry;
    }

    public TableGenerator(PluginRegistry pluginRegistry) {
        this(pluginRegistry.getGeneratorRegistry());
    }

    public TableGenerationResult generate(
            SchemaDefinition schema,
            long count,
            List<ConstraintDefinition> constraints,
            ConstraintValidatorRegistry constraintRegistry,
            Map<String, List<DataRow>> upstreamTables,
            DataWriter writer,
            GenerationOptions options) {
        ConstraintPipeline pipeline = new ConstraintPipeline(constraints, constraintRegistry);
        GenerationPipeline writePipeline = new GenerationPipeline(writer, options.batchSize());

        String tableName = schema.getTable() == null ? "unknown" : schema.getTable();
        List<DataRow> generatedRows = new ArrayList<>();
        long constraintFailedRows = 0;

        for (int rowIndex = 0; rowIndex < count; rowIndex++) {
            DataRow row = generateValidRow(schema, tableName, rowIndex, upstreamTables, pipeline, options.maxRetries());
            if (row == null) {
                constraintFailedRows++;
                continue;
            }
            generatedRows.add(row);
            writePipeline.accept(tableName, row);
        }

        writePipeline.flush(tableName);
        return new TableGenerationResult(
                generatedRows,
                writePipeline.getWrittenRows(),
                constraintFailedRows + writePipeline.getFailedRows());
    }

    private DataRow generateValidRow(
            SchemaDefinition schema,
            String tableName,
            int rowIndex,
            Map<String, List<DataRow>> upstreamTables,
            ConstraintPipeline pipeline,
            int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            DataRow row = generateRow(schema, tableName, rowIndex, upstreamTables);
            ConstraintResult result = pipeline.validate(new ConstraintContext(row, upstreamTables, Map.of()));
            if (result.isValid()) {
                return row;
            }
        }
        return null;
    }

    private DataRow generateRow(
            SchemaDefinition schema,
            String tableName,
            int rowIndex,
            Map<String, List<DataRow>> upstreamTables) {
        DataRow row = new DataRow();
        GenerationContext context = new GenerationContext(tableName, rowIndex, upstreamTables, row);
        for (FieldDefinition field : schema.getFields()) {
            Map<String, Object> generatorConfig = new HashMap<>(field.getGenerator());
            String strategy = String.valueOf(generatorConfig.getOrDefault("strategy", ""));
            Object value = generatorRegistry.get(strategy).generate(context, generatorConfig);
            row.set(field.getName(), value);
        }
        return row;
    }
}
