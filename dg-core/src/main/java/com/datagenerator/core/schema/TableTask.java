package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TableTask {

    private String name;
    private String schema;
    private SchemaDefinition schemaDefinition;
    private long count;
    private List<String> dependsOn = new ArrayList<>();
    private String constraints;
    private List<ConstraintDefinition> inlineConstraints = new ArrayList<>();
    private Map<String, Object> writer = new HashMap<>();
    private List<Map<String, Object>> writers = new ArrayList<>();

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

    public SchemaDefinition getSchemaDefinition() {
        return schemaDefinition;
    }

    public void setSchemaDefinition(SchemaDefinition schemaDefinition) {
        this.schemaDefinition = schemaDefinition;
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

    public List<Map<String, Object>> getWriters() {
        return Collections.unmodifiableList(writers);
    }

    public void setWriters(List<Map<String, Object>> writers) {
        if (writers == null || writers.isEmpty()) {
            this.writers = new ArrayList<>();
            return;
        }
        List<Map<String, Object>> copies = new ArrayList<>(writers.size());
        for (Map<String, Object> writerMap : writers) {
            copies.add(writerMap == null ? new HashMap<>() : new HashMap<>(writerMap));
        }
        this.writers = copies;
    }
}
