package com.datagenerator.web.dto;

import java.util.Map;

public class SchemaFieldResponse {

    private String name;
    private String type;
    private boolean primaryKey;
    private Map<String, Object> generator;

    public SchemaFieldResponse() {
    }

    public SchemaFieldResponse(String name, String type, Map<String, Object> generator) {
        this.name = name;
        this.type = type;
        this.generator = generator;
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
        return generator;
    }

    public void setGenerator(Map<String, Object> generator) {
        this.generator = generator;
    }
}
