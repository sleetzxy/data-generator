package com.datagenerator.core.engine;

public record GenerationOptions(int batchSize, int maxRetries) {

    public static final int DEFAULT_MAX_RETRIES = 3;

    public GenerationOptions {
        if (batchSize <= 0) {
            batchSize = GenerationPipeline.DEFAULT_BATCH_SIZE;
        }
        if (maxRetries < 0) {
            maxRetries = DEFAULT_MAX_RETRIES;
        }
    }

    public static GenerationOptions defaults() {
        return new GenerationOptions(GenerationPipeline.DEFAULT_BATCH_SIZE, DEFAULT_MAX_RETRIES);
    }
}
