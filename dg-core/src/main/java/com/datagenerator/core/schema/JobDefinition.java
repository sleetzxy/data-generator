package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class JobDefinition {

    private String job;
    private String constraints;
    private List<TableTask> tables = new ArrayList<>();

    public String getJob() {
        return job;
    }

    public void setJob(String job) {
        this.job = job;
    }

    public String getConstraints() {
        return constraints;
    }

    public void setConstraints(String constraints) {
        this.constraints = constraints;
    }

    public List<TableTask> getTables() {
        return Collections.unmodifiableList(tables);
    }

    public void setTables(List<TableTask> tables) {
        this.tables = tables == null ? new ArrayList<>() : new ArrayList<>(tables);
    }

    public Optional<TableTask> findTable(String name) {
        return tables.stream()
                .filter(table -> table.getName().equals(name))
                .findFirst();
    }
}
