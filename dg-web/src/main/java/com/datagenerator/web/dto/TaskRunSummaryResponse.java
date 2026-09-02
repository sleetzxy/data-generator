package com.datagenerator.web.dto;

public class TaskRunSummaryResponse {

    private String runId;
    private String configPath;
    /** 任务显示名：由服务层按 config_path 关联主表解析，任务已删除时为 null */
    private String displayName;
    private TaskRunStatus status;
    private String submittedAt;
    private String duration;
    private long totalRows;
    private long writtenRows;
    private String errorMessage;

    public TaskRunSummaryResponse() {
    }

    public TaskRunSummaryResponse(
            String runId,
            String configPath,
            TaskRunStatus status,
            String submittedAt,
            String duration,
            long totalRows,
            long writtenRows,
            String errorMessage) {
        this.runId = runId;
        this.configPath = configPath;
        this.status = status;
        this.submittedAt = submittedAt;
        this.duration = duration;
        this.totalRows = totalRows;
        this.writtenRows = writtenRows;
        this.errorMessage = errorMessage;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public TaskRunStatus getStatus() {
        return status;
    }

    public void setStatus(TaskRunStatus status) {
        this.status = status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(String submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public long getWrittenRows() {
        return writtenRows;
    }

    public void setWrittenRows(long writtenRows) {
        this.writtenRows = writtenRows;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
