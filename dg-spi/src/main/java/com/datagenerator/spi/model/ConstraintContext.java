package com.datagenerator.spi.model;

import java.util.List;
import java.util.Map;

/**
 * Context supplied to constraint validators.
 */
public record ConstraintContext(
        DataRow currentRow,
        Map<String, List<DataRow>> upstreamTables,
        Map<String, Object> bindings) {
}
