package com.datagenerator.api.dto;

public class JobProgress {

    private int totalTables;
    private int completedTables;
    private long totalRows;
    private long writtenRows;
    private long failedRows;

    public JobProgress() {
    }

    public JobProgress(int totalTables, int completedTables, long totalRows, long writtenRows, long failedRows) {
        this.totalTables = totalTables;
        this.completedTables = completedTables;
        this.totalRows = totalRows;
        this.writtenRows = writtenRows;
        this.failedRows = failedRows;
    }

    public int getTotalTables() {
        return totalTables;
    }

    public void setTotalTables(int totalTables) {
        this.totalTables = totalTables;
    }

    public int getCompletedTables() {
        return completedTables;
    }

    public void setCompletedTables(int completedTables) {
        this.completedTables = completedTables;
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

    public long getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(long failedRows) {
        this.failedRows = failedRows;
    }
}
