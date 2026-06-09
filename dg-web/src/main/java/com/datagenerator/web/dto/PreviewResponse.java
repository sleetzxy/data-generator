package com.datagenerator.web.dto;

import java.util.ArrayList;
import java.util.List;

public class PreviewResponse {

    private JobStatus status = JobStatus.COMPLETED;
    private String duration;
    private List<PreviewTableResponse> tables = new ArrayList<>();

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public List<PreviewTableResponse> getTables() {
        return tables;
    }

    public void setTables(List<PreviewTableResponse> tables) {
        this.tables = tables == null ? new ArrayList<>() : tables;
    }
}
