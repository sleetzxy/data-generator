package com.datagenerator.web.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JobSubmitRequest {

    private String jobConfig;
    private Map<String, Object> overrides = new HashMap<>();
    private Map<String, Object> writer = new HashMap<>();
    private List<Map<String, Object>> writers = new ArrayList<>();
    private JobOptions options;

    public String getJobConfig() {
        return jobConfig;
    }

    public void setJobConfig(String jobConfig) {
        this.jobConfig = jobConfig;
    }

    public Map<String, Object> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, Object> overrides) {
        this.overrides = overrides == null ? new HashMap<>() : overrides;
    }

    public Map<String, Object> getWriter() {
        return writer;
    }

    public void setWriter(Map<String, Object> writer) {
        this.writer = writer == null ? new HashMap<>() : writer;
    }

    public List<Map<String, Object>> getWriters() {
        return writers;
    }

    public void setWriters(List<Map<String, Object>> writers) {
        this.writers = writers == null ? new ArrayList<>() : writers;
    }

    public JobOptions getOptions() {
        return options;
    }

    public void setOptions(JobOptions options) {
        this.options = options;
    }
}
