package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JobDefinition {

    private String id;
    private String name;
    private String constraints;
    private List<ConstraintDefinition> inlineConstraints = new ArrayList<>();
    private Map<String, Object> writer = new HashMap<>();
    private List<TableTask> tables = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getConstraints() {
        return constraints;
    }

    public void setConstraints(String constraints) {
        this.constraints = constraints;
    }

    public List<ConstraintDefinition> getInlineConstraints() {
        return Collections.unmodifiableList(inlineConstraints);
    }

    public void setInlineConstraints(List<ConstraintDefinition> inlineConstraints) {
        this.inlineConstraints =
                inlineConstraints == null ? new ArrayList<>() : new ArrayList<>(inlineConstraints);
    }

    public Map<String, Object> getWriter() {
        return Collections.unmodifiableMap(writer);
    }

    public void setWriter(Map<String, Object> writer) {
        this.writer = writer == null ? new HashMap<>() : new HashMap<>(writer);
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
