package com.datagenerator.web.config;

/**
 * 运行时任务参数，由 application.yml 注入，作为 API 请求缺省值。
 */
public record JobRuntimeSettings(int syncThreshold, int batchSize, int threadPoolSize) {

    public static JobRuntimeSettings defaults() {
        return new JobRuntimeSettings(5000, 1000, 4);
    }
}
