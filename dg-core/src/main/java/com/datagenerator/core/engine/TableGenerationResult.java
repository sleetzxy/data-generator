package com.datagenerator.core.engine;

import com.datagenerator.spi.model.DataRow;

import java.util.List;

public record TableGenerationResult(
        List<DataRow> generatedRows,
        int writtenRows,
        long failedRows) {
}
