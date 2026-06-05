package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TableTask {

    private String name;
    private String schema;
    private long count;
    private List<String> dependsOn = new ArrayList<>();
    private String constraints;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public List<String> getDependsOn() {
        return Collections.unmodifiableList(dependsOn);
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn == null ? new ArrayList<>() : new ArrayList<>(dependsOn);
    }

    public String getConstraints() {
        return constraints;
    }

    public void setConstraints(String constraints) {
        this.constraints = constraints;
    }
}
