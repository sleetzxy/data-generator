package com.datagenerator.web.dto;

public class TableDetail {

    private String table;
    private long rows;
    private long failedRows;
    private String status;

    public TableDetail() {
    }

    public TableDetail(String table, long rows, String status) {
        this(table, rows, 0, status);
    }

    public TableDetail(String table, long rows, long failedRows, String status) {
        this.table = table;
        this.rows = rows;
        this.failedRows = failedRows;
        this.status = status;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public long getRows() {
        return rows;
    }

    public void setRows(long rows) {
        this.rows = rows;
    }

    public long getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(long failedRows) {
        this.failedRows = failedRows;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
