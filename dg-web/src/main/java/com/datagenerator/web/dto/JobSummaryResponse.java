package com.datagenerator.web.dto;

public class JobSummaryResponse {

    private String jobId;
    private String jobConfig;
    private JobStatus status;
    private String submittedAt;
    private String duration;
    private long totalRows;
    private long writtenRows;
    private String errorMessage;

    public JobSummaryResponse() {
    }

    public JobSummaryResponse(
            String jobId,
            String jobConfig,
            JobStatus status,
            String submittedAt,
            String duration,
            long totalRows,
            long writtenRows,
            String errorMessage) {
        this.jobId = jobId;
        this.jobConfig = jobConfig;
        this.status = status;
        this.submittedAt = submittedAt;
        this.duration = duration;
        this.totalRows = totalRows;
        this.writtenRows = writtenRows;
        this.errorMessage = errorMessage;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobConfig() {
        return jobConfig;
    }

    public void setJobConfig(String jobConfig) {
        this.jobConfig = jobConfig;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
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
