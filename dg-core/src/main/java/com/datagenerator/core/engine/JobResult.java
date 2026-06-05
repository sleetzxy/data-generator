package com.datagenerator.core.engine;

import java.util.List;

public record JobResult(
        long totalRows,
        long writtenRows,
        long failedRows,
        List<TableResult> details) {
}
