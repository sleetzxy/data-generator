package com.datagenerator.web.dto;

public class JobScheduleResponse {

    private boolean enabled;
    private String cron;
    private boolean editable;
    private String nextRunAt;

    public JobScheduleResponse() {
    }

    public JobScheduleResponse(boolean enabled, String cron, boolean editable, String nextRunAt) {
        this.enabled = enabled;
        this.cron = cron;
        this.editable = editable;
        this.nextRunAt = nextRunAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public boolean isEditable() {
        return editable;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public String getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(String nextRunAt) {
        this.nextRunAt = nextRunAt;
    }
}
