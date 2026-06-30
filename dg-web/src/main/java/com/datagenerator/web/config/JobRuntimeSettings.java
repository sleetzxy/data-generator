package com.datagenerator.web.config;

/**
 * 运行时任务参数，由 application.yml 注入，作为 API 请求缺省值。
 */
public record JobRuntimeSettings(int syncThreshold, int batchSize, int threadPoolSize, int generationParallelism) {

    public JobRuntimeSettings(int syncThreshold, int batchSize, int threadPoolSize) {
        this(syncThreshold, batchSize, threadPoolSize, 0);
    }

    public static JobRuntimeSettings defaults() {
        return new JobRuntimeSettings(5000, 1000, 4, 0);
    }

    /**
     * 造数并行度：显式配置 &gt; thread-pool-size &gt; CPU 核数 - 1。
     */
    public int effectiveGenerationParallelism() {
        if (generationParallelism > 0) {
            return generationParallelism;
        }
        if (threadPoolSize > 1) {
            return threadPoolSize;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    }
}
