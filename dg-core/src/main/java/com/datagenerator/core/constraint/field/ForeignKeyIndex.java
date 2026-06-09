package com.datagenerator.core.constraint.field;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.spi.model.DataRow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外键校验索引：将上游表 ref 字段值预建 HashSet，避免逐行 O(n) 扫描。
 */
public final class ForeignKeyIndex {

    public static final String BINDING_KEY = "foreignKeyIndex";

    private final Map<String, Set<Object>> indexes;

    private ForeignKeyIndex(Map<String, Set<Object>> indexes) {
        this.indexes = indexes;
    }

    /**
     * 根据约束定义与上游表数据构建索引；无 FK 约束时返回空索引。
     */
    public static ForeignKeyIndex build(
            Map<String, List<DataRow>> upstreamTables,
            List<ConstraintDefinition> constraints) {
        Map<String, Set<Object>> indexes = new HashMap<>();
        for (ConstraintDefinition constraint : constraints) {
            if (!"foreign_key".equalsIgnoreCase(constraint.getType())) {
                continue;
            }
            String refTable = constraint.getRefTable();
            String refField = constraint.getRefField();
            if (refTable == null || refTable.isBlank() || refField == null || refField.isBlank()) {
                continue;
            }
            String key = indexKey(refTable, refField);
            indexes.computeIfAbsent(key, ignored -> buildFieldIndex(upstreamTables.get(refTable), refField));
        }
        return new ForeignKeyIndex(indexes);
    }

    public boolean contains(String refTable, String refField, Object value) {
        Set<Object> values = indexes.get(indexKey(refTable, refField));
        return values != null && values.contains(value);
    }

    public boolean isEmpty() {
        return indexes.isEmpty();
    }

    private static Set<Object> buildFieldIndex(List<DataRow> rows, String refField) {
        Set<Object> values = new HashSet<>();
        if (rows == null) {
            return values;
        }
        for (DataRow row : rows) {
            Object value = row.get(refField);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static String indexKey(String refTable, String refField) {
        return refTable + "." + refField;
    }
}
