package com.datagenerator.core.engine;

import com.datagenerator.core.constraint.ConstraintPipeline;
import com.datagenerator.core.constraint.ConstraintValidationOutcome;
import com.datagenerator.core.constraint.ConstraintValidatorRegistry;
import com.datagenerator.core.generator.GeneratorRegistry;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.SeedDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;
import com.datagenerator.spi.writer.DataWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TableGenerator {

    private final GeneratorRegistry generatorRegistry;
    private final ReferenceDataLoader referenceDataLoader;
    private final YamlConfigLoader configLoader;

    public TableGenerator(GeneratorRegistry generatorRegistry) {
        this.generatorRegistry = generatorRegistry;
        this.referenceDataLoader = null;
        this.configLoader = null;
    }

    public TableGenerator(PluginRegistry pluginRegistry) {
        this(pluginRegistry, null);
    }

    public TableGenerator(PluginRegistry pluginRegistry, YamlConfigLoader configLoader) {
        this.generatorRegistry = pluginRegistry.getGeneratorRegistry();
        this.referenceDataLoader = pluginRegistry.getReferenceDataLoader();
        this.configLoader = configLoader;
    }

    public TableGenerationResult generate(
            SchemaDefinition schema,
            long count,
            List<ConstraintDefinition> constraints,
            ConstraintValidatorRegistry constraintRegistry,
            Map<String, List<DataRow>> upstreamTables,
            DataWriter writer,
            List<SeedDefinition> jobSeeds,
            GenerationOptions options) {
        return generate(
                schema,
                count,
                constraints,
                constraintRegistry,
                upstreamTables,
                writer,
                jobSeeds,
                options,
                BatchWrittenCallback.NOOP);
    }

    public TableGenerationResult generate(
            SchemaDefinition schema,
            long count,
            List<ConstraintDefinition> constraints,
            ConstraintValidatorRegistry constraintRegistry,
            Map<String, List<DataRow>> upstreamTables,
            DataWriter writer,
            List<SeedDefinition> jobSeeds,
            GenerationOptions options,
            BatchWrittenCallback batchWrittenCallback) {
        return generate(
                schema,
                count,
                constraints,
                constraintRegistry,
                upstreamTables,
                writer,
                jobSeeds,
                options,
                batchWrittenCallback,
                null);
    }

    public TableGenerationResult generate(
            SchemaDefinition schema,
            long count,
            List<ConstraintDefinition> constraints,
            ConstraintValidatorRegistry constraintRegistry,
            Map<String, List<DataRow>> upstreamTables,
            DataWriter writer,
            List<SeedDefinition> jobSeeds,
            GenerationOptions options,
            BatchWrittenCallback batchWrittenCallback,
            SeedRowSnapshotStore seedRowSnapshots) {
        ConstraintPipeline pipeline = new ConstraintPipeline(constraints, constraintRegistry, options.onConstraintFail());
        GenerationPipeline writePipeline =
                new GenerationPipeline(writer, options.batchSize(), batchWrittenCallback);

        String tableName = schema.getTable() == null ? "unknown" : schema.getTable();
        List<DataRow> generatedRows = new ArrayList<>();
        long constraintFailedRows = 0;

        List<SeedDefinition> sortedSeeds = jobSeeds == null || jobSeeds.isEmpty()
                ? List.of()
                : SeedDependencySorter.sort(new ArrayList<>(jobSeeds));
        SeedSampler seedSampler = sortedSeeds.isEmpty() || referenceDataLoader == null
                ? null
                : new SeedSampler(referenceDataLoader, configLoader, sortedSeeds);

        for (int rowIndex = 0; rowIndex < count; rowIndex++) {
            DataRow row = generateValidRow(
                    schema,
                    tableName,
                    rowIndex,
                    upstreamTables,
                    pipeline,
                    seedSampler,
                    seedRowSnapshots,
                    options.maxRetries());
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
            SeedSampler seedSampler,
            SeedRowSnapshotStore seedRowSnapshots,
            int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Map<String, DataRow> seedSamples = seedSampler == null
                    ? Map.of()
                    : seedSampler.sample(schema, rowIndex, seedRowSnapshots);
            DataRow row = generateRow(schema, tableName, rowIndex, upstreamTables, seedSamples);
            ConstraintValidationOutcome outcome = pipeline.validateRow(
                    new ConstraintContext(row, upstreamTables, Map.of()));
            if (outcome.isAccepted()) {
                return outcome.row();
            }
        }
        return null;
    }

    private DataRow generateRow(
            SchemaDefinition schema,
            String tableName,
            int rowIndex,
            Map<String, List<DataRow>> upstreamTables,
            Map<String, DataRow> seedSamples) {
        DataRow row = new DataRow();
        GenerationContext context = new GenerationContext(tableName, rowIndex, upstreamTables, row, seedSamples);
        for (FieldDefinition field : schema.getFields()) {
            Map<String, Object> generatorConfig = new HashMap<>(field.getGenerator());
            if (!generatorConfig.containsKey("field")) {
                generatorConfig.put("field", field.getName());
            }
            String strategy = String.valueOf(generatorConfig.getOrDefault("strategy", ""));
            Object value = generatorRegistry.get(strategy).generate(context, generatorConfig);
            row.set(field.getName(), value);
        }
        return row;
    }
}
