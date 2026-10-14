package com.datagenerator.web.storage;

import com.datagenerator.web.dto.TaskRunResponse;

/**
 * 任务运行 API DTO 与持久化模型互转。
 */
public final class TaskRunMapper {

    private TaskRunMapper() {
    }

    public static TaskRunRecord toRecord(TaskRunResponse response) {
        if (response == null) {
            return null;
        }
        return new TaskRunRecord(
                response.getRunId(),
                response.getStatus(),
                response.getProgress(),
                response.getDetails(),
                response.getDuration(),
                response.getConfigPath(),
                response.getSubmittedAt(),
                response.getErrorMessage(),
                response.getTriggerSource());
    }

    public static TaskRunResponse toResponse(TaskRunRecord record) {
        if (record == null) {
            return null;
        }
        TaskRunResponse response = new TaskRunResponse(
                record.getRunId(),
                record.getStatus(),
                record.getProgress(),
                record.getDetails(),
                record.getDuration(),
                record.getConfigPath(),
                record.getSubmittedAt(),
                record.getErrorMessage(),
                null);
        response.setTriggerSource(record.getTriggerSource());
        return response;
    }
}
