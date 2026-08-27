package com.datagenerator.core.engine;

/**
 * 任务执行过程中的进度通知，供 Web 层写入日志与持久化进度。
 */
public interface TaskRunExecutionListener {

    TaskRunExecutionListener NOOP = new TaskRunExecutionListener() {};

    default void onTableStarted(String tableName, int tableIndex, int totalTables, long plannedRows) {
    }

    default void onBatchWritten(
            String tableName,
            int batchWritten,
            int batchFailed,
            long tableWrittenRows,
            long tableFailedRows,
            long runWrittenRows,
            long runFailedRows) {
    }

    default void onTableCompleted(
            String tableName,
            long tableWrittenRows,
            long tableFailedRows,
            int completedTables,
            int totalTables,
            long runWrittenRows,
            long runFailedRows) {
    }
}
