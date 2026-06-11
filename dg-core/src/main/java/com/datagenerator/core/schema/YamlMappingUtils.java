package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class YamlMappingUtils {

    private YamlMappingUtils() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new ConfigLoadException("Expected map but got: " + value.getClass().getSimpleName());
        }
        Map<String, Object> result = new HashMap<>();
        rawMap.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asMapList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new ConfigLoadException("Expected list but got: " + value.getClass().getSimpleName());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            result.add(asMap(item));
        }
        return result;
    }

    static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static boolean parseBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new ConfigLoadException("Expected numeric value but got: " + value);
    }

    static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new ConfigLoadException("Expected numeric value but got: " + value);
    }

    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new ConfigLoadException("Expected list but got: " + value.getClass().getSimpleName());
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    static FieldDefinition toFieldDefinition(Map<String, Object> source) {
        FieldDefinition field = new FieldDefinition();
        field.setName(asString(source.get("name")));
        field.setType(asString(source.get("type")));
        field.setPrimaryKey(parseBoolean(source.get("primaryKey")));
        field.setGenerator(asMap(source.get("generator")));
        return field;
    }

    static SchemaDefinition toSchemaDefinition(Map<String, Object> source) {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable(asString(source.get("table")));
        schema.setConstraints(asString(source.get("constraints")));

        List<FieldDefinition> fields = new ArrayList<>();
        for (Map<String, Object> fieldSource : asMapList(source.get("fields"))) {
            fields.add(toFieldDefinition(fieldSource));
        }
        schema.setFields(fields);
        SchemaFieldValidator.validate(schema);
        return schema;
    }

    static List<ConstraintDefinition> toConstraintDefinitions(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?>) {
            List<ConstraintDefinition> constraints = new ArrayList<>();
            for (Map<String, Object> constraintSource : asMapList(value)) {
                constraints.add(toConstraintDefinition(constraintSource));
            }
            return constraints;
        }
        return List.of();
    }

    static TableTask toTableTask(Map<String, Object> source) {
        TableTask task = new TableTask();
        task.setName(asString(source.get("name")));
        Object schemaValue = source.get("schema");
        if (schemaValue instanceof Map<?, ?>) {
            task.setSchemaDefinition(toSchemaDefinition(asMap(schemaValue)));
        } else {
            task.setSchema(asString(schemaValue));
        }
        Object count = source.get("count");
        if (count != null) {
            task.setCount(asLong(count));
        }
        Object dependsOn = source.containsKey("depends_on") ? source.get("depends_on") : source.get("dependsOn");
        task.setDependsOn(asStringList(dependsOn));
        Object constraintsValue = source.get("constraints");
        if (constraintsValue instanceof List<?>) {
            task.setInlineConstraints(toConstraintDefinitions(constraintsValue));
        } else {
            task.setConstraints(asString(constraintsValue));
        }
        task.setWriter(asMap(source.get("writer")));
        return task;
    }

    static ScheduleDefinition toScheduleDefinition(Map<String, Object> source) {
        ScheduleDefinition schedule = new ScheduleDefinition();
        Object enabled = source.get("enabled");
        if (enabled instanceof Boolean bool) {
            schedule.setEnabled(bool);
        }
        schedule.setCron(asString(source.get("cron")));
        return schedule;
    }

    static SeedDefinition toSeedDefinition(Map<String, Object> source) {
        SeedDefinition seed = new SeedDefinition();
        seed.setName(asString(source.get("name")));
        Object linkValue = source.get("link");
        if (linkValue != null) {
            seed.setLink(toSeedLinkDefinition(asMap(linkValue)));
        }
        seed.setReader(asMap(source.get("reader")));
        seed.setReference(asString(source.get("reference")));
        seed.setTemplate(asMap(source.get("template")));
        return seed;
    }

    static SeedLinkDefinition toSeedLinkDefinition(Map<String, Object> source) {
        SeedLinkDefinition link = new SeedLinkDefinition();
        link.setSeed(asString(source.get("seed")));
        Object onValue = source.get("on");
        if (onValue != null && !(onValue instanceof Boolean)) {
            link.setOn(asString(onValue));
        }
        Object parentField = source.containsKey("parent_field") ? source.get("parent_field") : source.get("parentField");
        link.setParentField(asString(parentField));
        Object localField = source.containsKey("local_field") ? source.get("local_field") : source.get("localField");
        link.setLocalField(asString(localField));
        return link;
    }

    static ConstraintDefinition toConstraintDefinition(Map<String, Object> source) {
        ConstraintDefinition constraint = new ConstraintDefinition();
        constraint.setLevel(asString(source.get("level")));
        constraint.setField(asString(source.get("field")));
        constraint.setType(asString(source.get("type")));
        constraint.setExpression(asString(source.get("expression")));
        constraint.setLanguage(asString(source.get("language")));
        constraint.setMin(asDouble(source.get("min")));
        constraint.setMax(asDouble(source.get("max")));
        Object refTable = source.containsKey("ref_table") ? source.get("ref_table") : source.get("refTable");
        constraint.setRefTable(asString(refTable));
        Object refField = source.containsKey("ref_field") ? source.get("ref_field") : source.get("refField");
        constraint.setRefField(asString(refField));
        constraint.setFields(asStringList(source.get("fields")));
        constraint.setRule(asString(source.get("rule")));
        Object onFail = source.containsKey("on_fail") ? source.get("on_fail") : source.get("onFail");
        constraint.setOnFail(asString(onFail));
        Object geometryRef = source.containsKey("geometry_ref") ? source.get("geometry_ref") : source.get("geometryRef");
        constraint.setGeometryRef(asString(geometryRef));
        return constraint;
    }
}
