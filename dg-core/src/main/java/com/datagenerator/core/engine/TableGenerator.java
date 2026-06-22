package com.datagenerator.core.engine;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintPipeline;
import com.datagenerator.core.constraint.ConstraintValidationOutcome;
import com.datagenerator.core.constraint.ConstraintValidatorRegistry;
import com.datagenerator.core.constraint.field.ForeignKeyIndex;
import com.datagenerator.core.generator.GeneratorRegistry;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.generator.GeneratorOutputFormatter;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TableGenerator {

    private final PluginRegistry pluginRegistry;
    private final GeneratorRegistry generatorRegistry;
    private final ReferenceDataLoader referenceDataLoader;
    private final YamlConfigLoader configLoader;

    public TableGenerator(GeneratorRegistry generatorRegistry) {
        this.pluginRegistry = null;
        this.generatorRegistry = generatorRegistry;
        this.referenceDataLoader = null;
        this.configLoader = null;
    }

    public TableGenerator(PluginRegistry pluginRegistry) {
        this(pluginRegistry, null);
    }

    public TableGenerator(PluginRegistry pluginRegistry, YamlConfigLoader configLoader) {
        this.pluginRegistry = pluginRegistry;
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
                null,
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
                seedRowSnapshots,
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
            SeedRowSnapshotStore seedRowSnapshots,
            ConnectionRegistry connectionRegistry) {
        ConstraintPipeline pipeline = new ConstraintPipeline(constraints, constraintRegistry, options.onConstraintFail());
        CancellationChecker cancellationChecker = options.cancellationChecker();
        GenerationPipeline writePipeline =
                new GenerationPipeline(writer, options.batchSize(), batchWrittenCallback, cancellationChecker);
        ForeignKeyIndex foreignKeyIndex = ForeignKeyIndex.build(upstreamTables, constraints);
        Map<String, Object> constraintBindings = foreignKeyIndex.isEmpty()
                ? Map.of()
                : Map.of(ForeignKeyIndex.BINDING_KEY, foreignKeyIndex);

        String tableName = schema.getTable() == null ? "unknown" : schema.getTable();
        List<DataRow> generatedRows = new ArrayList<>();
        long constraintFailedRows = 0;

        List<SeedDefinition> sortedSeeds = jobSeeds == null || jobSeeds.isEmpty()
                ? List.of()
                : SeedDependencySorter.sort(new ArrayList<>(jobSeeds));
        ReferenceDataLoader seedReferenceLoader = resolveSeedReferenceLoader(connectionRegistry);
        SeedSampler seedSampler = sortedSeeds.isEmpty() || seedReferenceLoader == null
                ? null
                : new SeedSampler(seedReferenceLoader, configLoader, sortedSeeds);
        if (seedSampler != null) {
            seedSampler.preloadCaches(schema);
        }

        CancellationChecks.throwIfCancelled(cancellationChecker);

        if (shouldGenerateInParallel(count, options.generationParallelism())) {
            constraintFailedRows = generateRowsInParallel(
                    schema,
                    count,
                    tableName,
                    upstreamTables,
                    pipeline,
                    seedSampler,
                    seedRowSnapshots,
                    constraintBindings,
                    options,
                    generatedRows,
                    cancellationChecker);
        } else {
            for (int rowIndex = 0; rowIndex < count; rowIndex++) {
                if (rowIndex % 256 == 0) {
                    CancellationChecks.throwIfCancelled(cancellationChecker);
                }
                DataRow row = generateValidRow(
                        schema,
                        tableName,
                        rowIndex,
                        upstreamTables,
                        pipeline,
                        seedSampler,
                        seedRowSnapshots,
                        constraintBindings,
                        options.maxRetries());
                if (row == null) {
                    constraintFailedRows++;
                    continue;
                }
                generatedRows.add(row);
            }
        }

        for (DataRow row : generatedRows) {
            CancellationChecks.throwIfCancelled(cancellationChecker);
            writePipeline.accept(tableName, row);
        }

        writePipeline.flush(tableName);
        return new TableGenerationResult(
                generatedRows,
                writePipeline.getWrittenRows(),
                constraintFailedRows + writePipeline.getFailedRows());
    }

    private long generateRowsInParallel(
            SchemaDefinition schema,
            long count,
            String tableName,
            Map<String, List<DataRow>> upstreamTables,
            ConstraintPipeline pipeline,
            SeedSampler seedSampler,
            SeedRowSnapshotStore seedRowSnapshots,
            Map<String, Object> constraintBindings,
            GenerationOptions options,
            List<DataRow> generatedRows,
            CancellationChecker cancellationChecker) {
        int parallelism = options.generationParallelism();
        DataRow[] rows = new DataRow[(int) count];
        long[] failedRows = {0};
        int chunkSize = (int) ((count + parallelism - 1) / parallelism);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int chunk = 0; chunk < parallelism; chunk++) {
                int start = chunk * chunkSize;
                int end = (int) Math.min(count, (long) (chunk + 1) * chunkSize);
                if (start >= end) {
                    break;
                }
                futures.add(executor.submit(() -> {
                    for (int rowIndex = start; rowIndex < end; rowIndex++) {
                        if (rowIndex % 256 == 0 && Thread.currentThread().isInterrupted()) {
                            throw new JobCancelledException();
                        }
                        DataRow row = generateValidRow(
                                schema,
                                tableName,
                                rowIndex,
                                upstreamTables,
                                pipeline,
                                seedSampler,
                                seedRowSnapshots,
                                constraintBindings,
                                options.maxRetries());
                        if (row == null) {
                            synchronized (failedRows) {
                                failedRows[0]++;
                            }
                        } else {
                            rows[rowIndex] = row;
                        }
                    }
                }));
            }
            awaitParallelFutures(futures, cancellationChecker);
        } catch (JobCancelledException exception) {
            cancelParallelFutures(futures);
            executor.shutdownNow();
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelParallelFutures(futures);
            executor.shutdownNow();
            throw new JobCancelledException();
        } catch (ExecutionException exception) {
            cancelParallelFutures(futures);
            executor.shutdownNow();
            if (exception.getCause() instanceof JobCancelledException cancelled) {
                throw cancelled;
            }
            throw new IllegalStateException("Parallel row generation failed", exception.getCause());
        } finally {
            executor.shutdown();
        }

        for (DataRow row : rows) {
            if (row != null) {
                generatedRows.add(row);
            }
        }
        return failedRows[0];
    }

    private ReferenceDataLoader resolveSeedReferenceLoader(ConnectionRegistry connectionRegistry) {
        if (referenceDataLoader == null) {
            return null;
        }
        if (connectionRegistry == null || pluginRegistry == null) {
            return referenceDataLoader;
        }
        return new ReferenceDataLoader(pluginRegistry, connectionRegistry);
    }

    private static void awaitParallelFutures(List<Future<?>> futures, CancellationChecker cancellationChecker)
            throws InterruptedException, ExecutionException {
        for (Future<?> future : futures) {
            while (!future.isDone()) {
                CancellationChecks.throwIfCancelled(cancellationChecker);
                try {
                    future.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 继续轮询，以便及时响应取消
                }
            }
            future.get();
        }
    }

    private static void cancelParallelFutures(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private static boolean shouldGenerateInParallel(long count, int generationParallelism) {
        return count >= GenerationOptions.PARALLEL_ROW_THRESHOLD && generationParallelism > 1;
    }

    private DataRow generateValidRow(
            SchemaDefinition schema,
            String tableName,
            int rowIndex,
            Map<String, List<DataRow>> upstreamTables,
            ConstraintPipeline pipeline,
            SeedSampler seedSampler,
            SeedRowSnapshotStore seedRowSnapshots,
            Map<String, Object> constraintBindings,
            int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Map<String, DataRow> seedSamples = seedSampler == null
                    ? Map.of()
                    : seedSampler.sample(schema, rowIndex, seedRowSnapshots);
            DataRow row = generateRow(schema, tableName, rowIndex, upstreamTables, seedSamples);
            ConstraintValidationOutcome outcome = pipeline.validateRow(
                    new ConstraintContext(row, upstreamTables, constraintBindings));
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
            row.set(field.getName(), GeneratorOutputFormatter.apply(value, generatorConfig));
        }
        return row;
    }
}
