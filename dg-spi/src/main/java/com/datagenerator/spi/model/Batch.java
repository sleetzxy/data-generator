package com.datagenerator.spi.model;

import java.util.List;

/**
 * A batch of rows to write for a single table.
 */
public record Batch(String tableName, List<DataRow> rows) {
}
