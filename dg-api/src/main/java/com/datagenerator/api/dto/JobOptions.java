package com.datagenerator.api.dto;

public class JobOptions {

    private Integer batchSize;
    private Integer syncThreshold;
    private String onConstraintFail;
    private Integer maxRetries;

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
}
