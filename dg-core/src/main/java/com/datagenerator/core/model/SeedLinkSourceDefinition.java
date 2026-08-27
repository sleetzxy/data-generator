package com.datagenerator.core.model;

/**
 * 多数据源 link seed 的单条加载与匹配规则。
 */
public class SeedLinkSourceDefinition {

    /** 匹配方式：equals / path / contains */
    private String match;
    /** 加载结果中用于与父 seed 关联值匹配的列 */
    private String column;
    /** 预加载 SQL */
    private String query;
    /** 未写 query 时退化为 {@code SELECT * FROM table} */
    private String table;
    /**
     * 结果列映射：segment（bigroadcl_lkld）或 node（centerpoint）。
     * 未指定时，contains 匹配默认 node，path 默认 segment。
     */
    private String profile;

    public String getMatch() {
        return match;
    }

    public void setMatch(String match) {
        this.match = match;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String resolveLoadQuery() {
        if (query != null && !query.isBlank()) {
            return query.trim();
        }
        if (table != null && !table.isBlank()) {
            return "SELECT * FROM " + table.trim();
        }
        throw new ConfigLoadException("link source 需要 query 或 table");
    }

    public String resolveProfile() {
        if (profile != null && !profile.isBlank()) {
            return profile.trim().toLowerCase();
        }
        if ("contains".equalsIgnoreCase(match)) {
            return "node";
        }
        return "segment";
    }
}
