package com.datagenerator.core.engine;

public record GenerationOptions(int batchSize, int maxRetries, String onConstraintFail) {

    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final String DEFAULT_ON_FAIL = "reject";

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
    }

    public GenerationOptions(int batchSize, int maxRetries) {
        this(batchSize, maxRetries, DEFAULT_ON_FAIL);
    }

    public static GenerationOptions defaults() {
        return new GenerationOptions(GenerationPipeline.DEFAULT_BATCH_SIZE, DEFAULT_MAX_RETRIES, DEFAULT_ON_FAIL);
    }
}
