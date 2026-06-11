package com.datagenerator.core.schema;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FieldDefinition {

    private String name;
    private String type;
    private boolean primaryKey;
    private Map<String, Object> generator = new HashMap<>();

    public FieldDefinition() {
    }

    public FieldDefinition(String name, String type, Map<String, Object> generator) {
        this.name = name;
        this.type = type;
        setGenerator(generator);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public Map<String, Object> getGenerator() {
        return Collections.unmodifiableMap(generator);
    }

    public void setGenerator(Map<String, Object> generator) {
        this.generator = generator == null ? new HashMap<>() : new HashMap<>(generator);
    }
}
