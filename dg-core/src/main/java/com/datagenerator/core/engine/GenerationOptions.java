package com.datagenerator.core.engine;

public record GenerationOptions(
        int batchSize,
        int maxRetries,
        String onConstraintFail,
        int generationParallelism) {

    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final String DEFAULT_ON_FAIL = "reject";
    public static final int DEFAULT_GENERATION_PARALLELISM = 1;
    public static final int PARALLEL_ROW_THRESHOLD = 5_000;

    public GenerationOptions {
        if (batchSize <= 0) {
            batchSize = GenerationPipeline.DEFAULT_BATCH_SIZE;
        }
        if (maxRetries < 0) {
            maxRetries = DEFAULT_MAX_RETRIES;
        }
        if (onConstraintFail == null || onConstraintFail.isBlank()) {
            onConstraintFail = DEFAULT_ON_FAIL;
        }
        if (generationParallelism <= 0) {
            generationParallelism = DEFAULT_GENERATION_PARALLELISM;
        }
    }

    public GenerationOptions(int batchSize, int maxRetries, String onConstraintFail) {
        this(batchSize, maxRetries, onConstraintFail, DEFAULT_GENERATION_PARALLELISM);
    }

    public GenerationOptions(int batchSize, int maxRetries) {
        this(batchSize, maxRetries, DEFAULT_ON_FAIL, DEFAULT_GENERATION_PARALLELISM);
    }

    public static GenerationOptions defaults() {
        return new GenerationOptions(
                GenerationPipeline.DEFAULT_BATCH_SIZE,
                DEFAULT_MAX_RETRIES,
                DEFAULT_ON_FAIL,
                DEFAULT_GENERATION_PARALLELISM);
    }
}
