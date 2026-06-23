package com.datagenerator.web.dto;

public class JobOptions {

    private Integer batchSize;
    private Integer syncThreshold;
    private String onConstraintFail;
    private Integer maxRetries;
    /** 造数并行度，覆盖 application.yml 默认值 */
    private Integer generationParallelism;

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getSyncThreshold() {
        return syncThreshold;
    }

    public void setSyncThreshold(Integer syncThreshold) {
        this.syncThreshold = syncThreshold;
    }

    public String getOnConstraintFail() {
        return onConstraintFail;
    }

    public void setOnConstraintFail(String onConstraintFail) {
        this.onConstraintFail = onConstraintFail;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getGenerationParallelism() {
        return generationParallelism;
    }

    public void setGenerationParallelism(Integer generationParallelism) {
        this.generationParallelism = generationParallelism;
    }
}
