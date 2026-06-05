package com.datagenerator.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JobResponse {

    private String jobId;
    private JobStatus status;
    private JobProgress progress;
    private String duration;
    private List<TableDetail> details = new ArrayList<>();
    private Map<String, List<Map<String, Object>>> rows;

    public JobResponse() {
    }

    public JobResponse(
            String jobId,
            JobStatus status,
            JobProgress progress,
            List<TableDetail> details,
            String duration,
            Map<String, List<Map<String, Object>>> rows) {
        this.jobId = jobId;
        this.status = status;
        this.progress = progress;
        this.details = details == null ? new ArrayList<>() : details;
        this.duration = duration;
        this.rows = rows;
    }

    public static JobResponse completed(String jobId, long totalRows) {
        JobProgress progress = new JobProgress(1, 1, totalRows, totalRows, 0);
        return new JobResponse(jobId, JobStatus.COMPLETED, progress, List.of(), "0ms", null);
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public JobProgress getProgress() {
        return progress;
    }

    public void setProgress(JobProgress progress) {
        this.progress = progress;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
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
}
