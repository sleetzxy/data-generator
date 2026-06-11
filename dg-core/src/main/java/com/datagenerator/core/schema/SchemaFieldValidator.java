package com.datagenerator.core.schema;

import java.util.Map;
import java.util.Set;

/**
 * Schema 字段级配置校验。
 */
public final class SchemaFieldValidator {

    private static final Set<String> STRING_TYPES = Set.of(
            "varchar",
            "char",
            "character",
            "text",
            "string",
            "clob",
            "nvarchar",
            "nchar",
            "ntext",
            "longvarchar",
            "mediumtext",
            "longtext",
            "tinytext");

    private SchemaFieldValidator() {
    }

    public static void validate(SchemaDefinition schema) {
        if (schema == null) {
            return;
        }
        String table = schema.getTable();
        for (FieldDefinition field : schema.getFields()) {
            validateField(field, table);
        }
    }

    private static void validateField(FieldDefinition field, String table) {
        Map<String, Object> generator = field.getGenerator();
        Object prefixValue = generator.get("prefix");
        if (prefixValue == null || String.valueOf(prefixValue).isBlank()) {
            return;
        }
        if (!isStringType(field.getType())) {
            String tableLabel = table == null || table.isBlank() ? "schema" : "table '" + table + "'";
            throw new ConfigLoadException(
                    "Field '" + field.getName() + "' in " + tableLabel
                            + " uses generator.prefix but type must be a string type"
                            + " (VARCHAR, CHAR, TEXT, etc.), got: " + field.getType());
        }
    }

    static boolean isStringType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String normalized = type.trim().toLowerCase();
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        return STRING_TYPES.contains(normalized);
    }
}
