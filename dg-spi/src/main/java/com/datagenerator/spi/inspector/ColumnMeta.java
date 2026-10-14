package com.datagenerator.spi.inspector;

/**
 * 列元数据，由 {@link ConnectionInspector#listColumns} 返回。
 */
public record ColumnMeta(
        String name,
        String typeName,
        boolean nullable,
        boolean primaryKey) {}
