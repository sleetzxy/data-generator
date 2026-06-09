package com.datagenerator.core.schema;

public class SeedLinkDefinition {

    private String seed;
    private String on;
    private String parentField;
    private String localField;

    public String getSeed() {
        return seed;
    }

    public void setSeed(String seed) {
        this.seed = seed;
    }

    public String getOn() {
        return on;
    }

    public void setOn(String on) {
        this.on = on;
    }

    public String getParentField() {
        return parentField;
    }

    public void setParentField(String parentField) {
        this.parentField = parentField;
    }

    public String getLocalField() {
        return localField;
    }

    public void setLocalField(String localField) {
        this.localField = localField;
    }

    /** 解析父 seed 采样行中用于关联的列名。 */
    public String resolveParentColumn() {
        if (on != null && !on.isBlank()) {
            return on;
        }
        if (parentField != null && !parentField.isBlank()) {
            return parentField;
        }
        throw new ConfigLoadException("seed link requires 'on' or 'parent_field'");
    }
}
