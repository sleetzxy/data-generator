package com.datagenerator.spi.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable row of field name to value mappings.
 */
public class DataRow {

    private final Map<String, Object> fields;

    public DataRow() {
        this.fields = new HashMap<>();
    }

    public DataRow(Map<String, Object> fields) {
        this.fields = new HashMap<>(fields);
    }

    public Object get(String name) {
        return fields.get(name);
    }

    public void set(String name, Object value) {
        fields.put(name, value);
    }

    public Map<String, Object> getFields() {
        return Collections.unmodifiableMap(fields);
    }
}
