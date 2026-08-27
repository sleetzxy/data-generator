package com.datagenerator.core.model;

public class SeedLinkDefinition {

    private String seed;
    private String on;
    private String parentField;
    private String localField;
    /** 单数据源匹配：equals / path / contains */
    private String match;
    /** 多数据源匹配规则（与 match 二选一） */
    private java.util.List<SeedLinkSourceDefinition> sources = new java.util.ArrayList<>();

    public String getMatch() {
        return match;
    }

    public void setMatch(String match) {
        this.match = match;
    }

    public java.util.List<SeedLinkSourceDefinition> getSources() {
        return java.util.Collections.unmodifiableList(sources);
    }

    public void setSources(java.util.List<SeedLinkSourceDefinition> sources) {
        this.sources = sources == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(sources);
    }

    public boolean hasExplicitMatchRules() {
        return (match != null && !match.isBlank()) || !sources.isEmpty();
    }

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

    /** 解析加载结果中用于匹配的列名；默认与 parent 列同名。 */
    public String resolveLocalColumn() {
        if (localField != null && !localField.isBlank()) {
            return localField;
        }
        return resolveParentColumn();
    }
}
