package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SchemaDefinition {

    private String table;
    private String constraints;
    private List<FieldDefinition> fields = new ArrayList<>();

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

    public List<FieldDefinition> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public void setFields(List<FieldDefinition> fields) {
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }
}
