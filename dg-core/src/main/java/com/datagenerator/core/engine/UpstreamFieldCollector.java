package com.datagenerator.core.engine;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.spi.model.DataRow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收集下游表对上游表的 reference / foreign_key 所需字段，用于 upstream 行瘦身。
 */
public final class UpstreamFieldCollector {

    private UpstreamFieldCollector() {
    }

    public static Set<String> collectRequiredFields(
            String upstreamTableName,
            SchemaDefinition downstreamSchema,
            List<ConstraintDefinition> downstreamConstraints) {
        Set<String> fields = new HashSet<>();
        collectFromSchema(upstreamTableName, downstreamSchema, fields);
        for (ConstraintDefinition constraint : downstreamConstraints) {
            if (!"foreign_key".equalsIgnoreCase(constraint.getType())) {
                continue;
            }
            if (upstreamTableName.equals(constraint.getRefTable()) && constraint.getRefField() != null) {
                fields.add(constraint.getRefField());
            }
        }
        return fields;
    }

    public static List<DataRow> slimRows(List<DataRow> rows, Set<String> fields) {
        if (fields.isEmpty() || rows.isEmpty()) {
            return rows;
        }
        return rows.stream().map(row -> copyFields(row, fields)).toList();
    }

    private static void collectFromSchema(
            String upstreamTableName, SchemaDefinition schema, Set<String> fields) {
        if (schema == null || schema.getFields() == null) {
            return;
        }
        for (FieldDefinition field : schema.getFields()) {
            Map<String, Object> generator = field.getGenerator();
            if (generator.isEmpty()) {
                continue;
            }
            if (!"reference".equals(String.valueOf(generator.get("strategy")))) {
                continue;
            }
            if (!upstreamTableName.equals(String.valueOf(generator.get("source")))) {
                continue;
            }
            Object refField = generator.get("field");
            if (refField != null && !String.valueOf(refField).isBlank()) {
                fields.add(String.valueOf(refField));
            }
        }
    }

    private static DataRow copyFields(DataRow row, Set<String> fields) {
        DataRow slim = new DataRow();
        for (String field : fields) {
            if (row.getFields().containsKey(field)) {
                slim.set(field, row.get(field));
            }
        }
        return slim;
    }
}
