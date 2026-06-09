package com.datagenerator.core.engine;

/**
 * 单表批量写入完成后的回调。
 */
@FunctionalInterface
public interface BatchWrittenCallback {

    BatchWrittenCallback NOOP = (tableName, batchWritten, batchFailed, tableWrittenRows, tableFailedRows) -> {};

    void onBatchWritten(
            String tableName,
            int batchWritten,
            int batchFailed,
            int tableWrittenRows,
            int tableFailedRows);
}
