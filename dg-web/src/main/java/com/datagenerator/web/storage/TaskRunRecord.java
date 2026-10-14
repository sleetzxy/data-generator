package com.datagenerator.web.storage;

import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TriggerSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务运行持久化模型（与 API 层 {@code TaskRunResponse} 解耦）。
 */
public class TaskRunRecord {

    private String runId;
    private TaskRunStatus status;
    private TaskRunProgress progress;
    private String duration;
    private String configPath;
    private String submittedAt;
    private String errorMessage;
    private List<TableDetail> details = new ArrayList<>();
    private TriggerSource triggerSource;

    public TaskRunRecord() {
    }

    public TaskRunRecord(
            String runId,
            TaskRunStatus status,
            TaskRunProgress progress,
            List<TableDetail> details,
            String duration,
            String configPath,
            String submittedAt,
            String errorMessage,
            TriggerSource triggerSource) {
        this.runId = runId;
        this.status = status;
        this.progress = progress;
        this.details = details == null ? new ArrayList<>() : new ArrayList<>(details);
        this.duration = duration;
        this.configPath = configPath;
        this.submittedAt = submittedAt;
        this.errorMessage = errorMessage;
        this.triggerSource = triggerSource;
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
        this.details = details == null ? new ArrayList<>() : new ArrayList<>(details);
    }

    public TriggerSource getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(TriggerSource triggerSource) {
        this.triggerSource = triggerSource;
    }
}
