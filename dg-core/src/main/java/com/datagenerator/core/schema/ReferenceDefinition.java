package com.datagenerator.core.schema;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ReferenceDefinition {

    private String name;
    private Map<String, Object> reader = new HashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getReader() {
        return Collections.unmodifiableMap(reader);
    }

    public void setReader(Map<String, Object> reader) {
        this.reader = reader == null ? new HashMap<>() : new HashMap<>(reader);
    }
}
