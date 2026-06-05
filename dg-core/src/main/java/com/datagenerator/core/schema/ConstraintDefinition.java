package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConstraintDefinition {

    private String level;
    private String field;
    private String type;
    private String expression;
    private String language;
    private Double min;
    private Double max;
    private String refTable;
    private String refField;
    private List<String> fields = new ArrayList<>();
    private String rule;
    private String onFail;
    private String geometryRef;

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Double getMin() {
        return min;
    }

    public void setMin(Double min) {
        this.min = min;
    }

    public Double getMax() {
        return max;
    }

    public void setMax(Double max) {
        this.max = max;
    }

    public String getRefTable() {
        return refTable;
    }

    public void setRefTable(String refTable) {
        this.refTable = refTable;
    }

    public String getRefField() {
        return refField;
    }

    public void setRefField(String refField) {
        this.refField = refField;
    }

    public List<String> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public void setFields(List<String> fields) {
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public String getOnFail() {
        return onFail;
    }

    public void setOnFail(String onFail) {
        this.onFail = onFail;
    }

    public String getGeometryRef() {
        return geometryRef;
    }

    public void setGeometryRef(String geometryRef) {
        this.geometryRef = geometryRef;
    }
}
