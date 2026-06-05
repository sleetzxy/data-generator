package com.datagenerator.core.constraint;

import com.datagenerator.core.schema.ConstraintDefinition;

import java.util.HashMap;
import java.util.Map;

final class ConstraintRuleMapper {

    private ConstraintRuleMapper() {
    }

    static Map<String, Object> toRuleConfig(ConstraintDefinition definition) {
        Map<String, Object> config = new HashMap<>();
        if (definition.getField() != null) {
            config.put("field", definition.getField());
        }
        if (definition.getType() != null) {
            config.put("type", definition.getType());
        }
        if (definition.getExpression() != null) {
            config.put("expression", definition.getExpression());
        }
        if (definition.getLanguage() != null) {
            config.put("language", definition.getLanguage());
        }
        if (definition.getMin() != null) {
            config.put("min", definition.getMin());
        }
        if (definition.getMax() != null) {
            config.put("max", definition.getMax());
        }
        if (definition.getRefTable() != null) {
            config.put("ref_table", definition.getRefTable());
        }
        if (definition.getRefField() != null) {
            config.put("ref_field", definition.getRefField());
        }
        if (!definition.getFields().isEmpty()) {
            config.put("fields", definition.getFields());
        }
        if (definition.getRule() != null) {
            config.put("rule", definition.getRule());
        }
        if (definition.getOnFail() != null) {
            config.put("on_fail", definition.getOnFail());
        }
        if (definition.getGeometryRef() != null) {
            config.put("geometry_ref", definition.getGeometryRef());
        }
        return config;
    }
}
