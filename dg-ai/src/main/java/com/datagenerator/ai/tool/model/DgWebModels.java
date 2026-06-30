package com.datagenerator.ai.tool.model;

import java.util.List;
import java.util.Map;

/** dg-web REST API 与 Tool 层共享的数据模型。 */
public final class DgWebModels {

    private DgWebModels() {
    }

    public record ConnectionInfo(String name, String type) {
    }

    public record JobSummary(String id, String name, String fileName) {
    }

    public record JobDetail(String id, String name, String fileName, String yaml) {
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    public record CreateJobRequest(String name, String displayName, String content) {
    }

    public record UpdateJobRequest(String displayName, String content) {
    }

    public record JobSchedule(boolean enabled, String cron, boolean editable, String nextRunAt) {
    }

    public record SchemaDetail(
            String table,
            String constraints,
            Map<String, Object> seed,
            List<SchemaField> fields) {
    }

    public record SchemaField(String name, String type, boolean primaryKey, Map<String, Object> generator) {
    }

    public record PreviewResult(String status, String duration, List<PreviewTable> tables) {
    }

    public record PreviewTable(String name, int rowCount, List<Map<String, Object>> rows) {
    }

    public record JobListPage(List<SubmittedJobSummary> items, long total, int page, int size) {
    }

    public record SubmittedJobSummary(String jobId, String status, String submittedAt) {
    }

    public record JobStatus(
            String jobId,
            String status,
            String duration,
            String errorMessage,
            Map<String, Object> progress) {
    }

    public record JobLogEntry(String timestamp, String level, String message) {
    }
}
