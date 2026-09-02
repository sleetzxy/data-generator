package com.datagenerator.web.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TaskRunResponse {

    private String runId;
    private TaskRunStatus status;
    private TaskRunProgress progress;
    private String duration;
    private String configPath;
    /** 任务显示名：由服务层按 config_path 关联主表解析，任务已删除时为 null */
    private String displayName;
    private String submittedAt;
    private String errorMessage;
    private List<TableDetail> details = new ArrayList<>();
    private Map<String, List<Map<String, Object>>> rows;
    private TriggerSource triggerSource;

    public TaskRunResponse() {
    }

    public TaskRunResponse(
            String runId,
            TaskRunStatus status,
            TaskRunProgress progress,
            List<TableDetail> details,
            String duration,
            Map<String, List<Map<String, Object>>> rows) {
        this(runId, status, progress, details, duration, null, null, null, rows);
    }

    public TaskRunResponse(
            String runId,
            TaskRunStatus status,
            TaskRunProgress progress,
            List<TableDetail> details,
            String duration,
            String configPath,
            String submittedAt,
            String errorMessage,
            Map<String, List<Map<String, Object>>> rows) {
        this.runId = runId;
        this.status = status;
        this.progress = progress;
        this.details = details == null ? new ArrayList<>() : details;
        this.duration = duration;
        this.configPath = configPath;
        this.submittedAt = submittedAt;
        this.errorMessage = errorMessage;
        this.rows = rows;
    }

    public static TaskRunResponse completed(String runId, long totalRows) {
        TaskRunProgress progress = new TaskRunProgress(1, 1, totalRows, totalRows, 0);
        return new TaskRunResponse(runId, TaskRunStatus.COMPLETED, progress, List.of(), "0ms", null);
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public TaskRunStatus getStatus() {
        return status;
    }

    public void setStatus(TaskRunStatus status) {
        this.status = status;
    }

    public TaskRunProgress getProgress() {
        return progress;
    }

    public void setProgress(TaskRunProgress progress) {
        this.progress = progress;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
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

    public String getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(String submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<TableDetail> getDetails() {
        return details;
    }

    public void setDetails(List<TableDetail> details) {
        this.details = details == null ? new ArrayList<>() : details;
    }

    public Map<String, List<Map<String, Object>>> getRows() {
        return rows;
    }

    public void setRows(Map<String, List<Map<String, Object>>> rows) {
        this.rows = rows;
    }

    public TriggerSource getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(TriggerSource triggerSource) {
        this.triggerSource = triggerSource;
    }
}
