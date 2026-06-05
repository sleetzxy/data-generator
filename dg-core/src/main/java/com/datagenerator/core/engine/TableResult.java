package com.datagenerator.core.engine;

public record TableResult(String table, long rows, long failedRows, String status) {
}
