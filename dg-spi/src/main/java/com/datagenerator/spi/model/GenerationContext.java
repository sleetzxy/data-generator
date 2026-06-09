package com.datagenerator.spi.model;

import java.util.List;
import java.util.Map;

/**
 * Context supplied to value generators while building a row.
 */
public record GenerationContext(
        String tableName,
        int rowIndex,
        Map<String, List<DataRow>> upstreamTables,
        DataRow rowBeingBuilt,
        Map<String, DataRow> seedSamples) {

    public GenerationContext(
            String tableName,
            int rowIndex,
            Map<String, List<DataRow>> upstreamTables,
            DataRow rowBeingBuilt) {
        this(tableName, rowIndex, upstreamTables, rowBeingBuilt, Map.of());
    }
}
