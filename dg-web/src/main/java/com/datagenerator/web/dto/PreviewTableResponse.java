package com.datagenerator.web.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PreviewTableResponse {

    private String tableName;
    private String schemaTable;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSchemaTable() {
        return schemaTable;
    }

    public void setSchemaTable(String schemaTable) {
        this.schemaTable = schemaTable;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns == null ? new ArrayList<>() : columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
    }
}
