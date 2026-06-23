package com.datagenerator.ai.port;

import java.util.List;
import java.util.Map;

public interface SchemaCatalogPort {

    List<String> listSchemas();

    SchemaDetail getSchema(String name);

    record SchemaDetail(
            String table,
            String constraints,
            Map<String, Object> seed,
            List<SchemaField> fields) {}

    record SchemaField(String name, String type, boolean primaryKey, Map<String, Object> generator) {}
}
