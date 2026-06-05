package com.datagenerator.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SchemaResponse {

    private String table;
    private String constraints;
    private Map<String, Object> seed;
    private List<SchemaFieldResponse> fields = new ArrayList<>();

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getConstraints() {
        return constraints;
    }

    public void setConstraints(String constraints) {
        this.constraints = constraints;
    }

    public Map<String, Object> getSeed() {
        return seed;
    }

    public void setSeed(Map<String, Object> seed) {
        this.seed = seed;
    }

    public List<SchemaFieldResponse> getFields() {
        return fields;
    }

    public void setFields(List<SchemaFieldResponse> fields) {
        this.fields = fields == null ? new ArrayList<>() : fields;
    }
}
