package com.datagenerator.ai.tool.impl.web;

import com.datagenerator.ai.tool.impl.model.DgWebModels.ConnectionInfo;
import com.datagenerator.ai.tool.impl.model.DgWebModels.CreateJobRequest;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobDetail;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobListPage;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobLogEntry;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobSchedule;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobStatus;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobSummary;
import com.datagenerator.ai.tool.impl.model.DgWebModels.PreviewResult;
import com.datagenerator.ai.tool.impl.model.DgWebModels.PreviewTable;
import com.datagenerator.ai.tool.impl.model.DgWebModels.SchemaDetail;
import com.datagenerator.ai.tool.impl.model.DgWebModels.SchemaField;
import com.datagenerator.ai.tool.impl.model.DgWebModels.SubmittedJobSummary;
import com.datagenerator.ai.tool.impl.model.DgWebModels.UpdateJobRequest;
import com.datagenerator.ai.tool.impl.model.DgWebModels.ValidationResult;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** 通过 RestTemplate 回调 dg-web 现有 REST API。 */
public class DataGeneratorWebClient {

    public static final String SERVICE_AUTH_HEADER = "X-DG-Service-Auth";
    private static final int MAX_ATTEMPTS = 2;

    private static final String CONNECTIONS_PATH = "/api/v1/config/connections";
    private static final String JOB_DEFINITIONS_PATH = "/api/v1/job-definitions";
    private static final String SCHEMAS_PATH = "/api/v1/schemas";
    private static final String PREVIEW_PATH = "/api/v1/preview";
    private static final String JOBS_PATH = "/api/v1/jobs";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String serviceAuthToken;

    public DataGeneratorWebClient(RestTemplate restTemplate, String baseUrl, String serviceAuthToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.serviceAuthToken = serviceAuthToken;
    }

    public List<ConnectionInfo> listConnections() {
        return get(CONNECTIONS_PATH, new ParameterizedTypeReference<>() {});
    }

    public List<JobSummary> listJobs() {
        List<JobDefinitionDto> definitions = get(JOB_DEFINITIONS_PATH, new ParameterizedTypeReference<>() {});
        return definitions.stream()
                .map(item -> new JobSummary(item.id(), item.name(), item.fileName()))
                .toList();
    }

    public JobDetail findJob(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        try {
            JobDefinitionDto definition = get(
                    JOB_DEFINITIONS_PATH + "/" + fileName.trim(), new ParameterizedTypeReference<>() {});
            return toDetail(definition);
        } catch (IllegalStateException exception) {
            if (isNotFound(exception)) {
                return null;
            }
            throw exception;
        }
    }

    private static boolean isNotFound(IllegalStateException exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("HTTP 404")) {
            return true;
        }
        Throwable cause = exception.getCause();
        return cause instanceof HttpStatusCodeException http && http.getStatusCode().value() == 404;
    }

    public ValidationResult validateYaml(String yaml) {
        return post(JOB_DEFINITIONS_PATH + "?validateOnly=true", new ContentRequest(yaml), ValidationResult.class);
    }

    public JobDetail createJob(CreateJobRequest request) {
        JobDefinitionDto created = postCreated(
                JOB_DEFINITIONS_PATH,
                new CreateRequest(request.name(), request.displayName(), request.content()),
                new ParameterizedTypeReference<>() {});
        return toDetail(created);
    }

    public JobDetail updateJob(String fileName, UpdateJobRequest request) {
        JobDefinitionDto updated = exchange(
                HttpMethod.PUT,
                JOB_DEFINITIONS_PATH + "/" + fileName.trim(),
                new UpdateRequest(request.displayName(), request.content()),
                new ParameterizedTypeReference<>() {},
                200);
        return toDetail(updated);
    }

    public void deleteJob(String fileName) {
        delete(JOB_DEFINITIONS_PATH + "/" + fileName.trim());
    }

    public JobSchedule getSchedule(String fileName) {
        ScheduleDto schedule = get(
                JOB_DEFINITIONS_PATH + "/" + fileName.trim() + "/schedule", new ParameterizedTypeReference<>() {});
        return new JobSchedule(schedule.enabled(), schedule.cron(), schedule.editable(), schedule.nextRunAt());
    }

    public List<String> listSchemas() {
        return get(SCHEMAS_PATH, new ParameterizedTypeReference<>() {});
    }

    public SchemaDetail getSchema(String name) {
        SchemaDto schema = get(SCHEMAS_PATH + "/" + name.trim(), new ParameterizedTypeReference<>() {});
        List<SchemaField> fields = schema.fields() == null
                ? List.of()
                : schema.fields().stream()
                        .map(field -> new SchemaField(
                                field.name(),
                                field.type(),
                                field.primaryKey(),
                                field.generator()))
                        .toList();
        return new SchemaDetail(schema.table(), schema.constraints(), schema.seed(), fields);
    }

    public PreviewResult preview(String jobConfigYaml, int limitPerTable, List<String> tableNames) {
        PreviewRequest request = new PreviewRequest(
                jobConfigYaml,
                new PreviewOptions(limitPerTable, tableNames == null ? List.of() : tableNames));
        PreviewDto response = post(PREVIEW_PATH, request, PreviewDto.class);
        List<PreviewTable> tables = response.tables() == null
                ? List.of()
                : response.tables().stream()
                        .map(table -> new PreviewTable(
                                table.tableName(),
                                table.rows() == null ? 0 : table.rows().size(),
                                table.rows()))
                        .toList();
        return new PreviewResult(
                response.status() == null ? "UNKNOWN" : response.status(),
                response.duration(),
                tables);
    }

    public JobListPage listSubmittedJobs(int page, int size) {
        JobListDto response = get(
                JOBS_PATH + "?page=" + page + "&size=" + size, new ParameterizedTypeReference<>() {});
        List<SubmittedJobSummary> items = response.items() == null
                ? List.of()
                : response.items().stream()
                        .map(item -> new SubmittedJobSummary(
                                item.jobId(),
                                item.status() == null ? null : item.status(),
                                item.submittedAt()))
                        .toList();
        return new JobListPage(items, response.total(), response.page(), response.size());
    }

    public JobStatus submitJob(String jobConfigYaml) {
        JobResponseDto response = exchange(
                HttpMethod.POST,
                JOBS_PATH,
                new SubmitRequest(jobConfigYaml),
                new ParameterizedTypeReference<>() {},
                200,
                202);
        return toStatus(response);
    }

    public JobStatus getSubmittedJob(String jobId) {
        return toStatus(get(JOBS_PATH + "/" + jobId, new ParameterizedTypeReference<>() {}));
    }

    public List<JobLogEntry> getSubmittedJobLogs(String jobId) {
        List<JobLogDto> logs = get(JOBS_PATH + "/" + jobId + "/logs", new ParameterizedTypeReference<>() {});
        return logs.stream()
                .map(log -> new JobLogEntry(log.timestamp(), log.level(), log.message()))
                .toList();
    }

    public void cancelSubmittedJob(String jobId) {
        delete(JOBS_PATH + "/" + jobId);
    }

    private JobDetail toDetail(JobDefinitionDto definition) {
        return new JobDetail(
                definition.id(),
                definition.name(),
                definition.fileName(),
                definition.content());
    }

    private JobStatus toStatus(JobResponseDto response) {
        Map<String, Object> progress = response.progress() == null
                ? Map.of()
                : Map.of(
                        "totalTables", response.progress().totalTables(),
                        "completedTables", response.progress().completedTables(),
                        "totalRows", response.progress().totalRows(),
                        "writtenRows", response.progress().writtenRows(),
                        "failedRows", response.progress().failedRows());
        return new JobStatus(
                response.jobId(),
                response.status() == null ? null : response.status(),
                response.duration(),
                response.errorMessage(),
                progress);
    }

    private <T> T get(String path, ParameterizedTypeReference<T> typeReference) {
        return exchange(HttpMethod.GET, path, null, typeReference, 200);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return exchange(HttpMethod.POST, path, body, responseType, 200);
    }

    private <T> T postCreated(String path, Object body, ParameterizedTypeReference<T> typeReference) {
        return exchange(HttpMethod.POST, path, body, typeReference, 201);
    }

    private <T> T exchange(
            HttpMethod method,
            String path,
            Object body,
            ParameterizedTypeReference<T> typeReference,
            int... expectedStatuses) {
        return executeWithRetry(method, path, body, response -> {
            if (!isExpectedStatus(response.getStatusCode().value(), expectedStatuses)) {
                throw httpError(method, path, response.getStatusCode().value(), response.getBody());
            }
            return response.getBody();
        }, typeReference);
    }

    private <T> T exchange(HttpMethod method, String path, Object body, Class<T> responseType, int... expectedStatuses) {
        return executeWithRetry(method, path, body, response -> {
            if (!isExpectedStatus(response.getStatusCode().value(), expectedStatuses)) {
                throw httpError(method, path, response.getStatusCode().value(), response.getBody());
            }
            return response.getBody();
        }, responseType);
    }

    private void delete(String path) {
        executeWithRetry(
                HttpMethod.DELETE,
                path,
                null,
                response -> {
                    if (response.getStatusCode().value() != 204) {
                        throw httpError(HttpMethod.DELETE, path, response.getStatusCode().value(), response.getBody());
                    }
                    return null;
                },
                Void.class);
    }

    private <T, R> R executeWithRetry(
            HttpMethod method,
            String path,
            Object body,
            ResponseHandler<R, T> handler,
            ParameterizedTypeReference<T> typeReference) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<T> response =
                        restTemplate.exchange(url(path), method, entity(body), typeReference);
                return handler.handle(response);
            } catch (RestClientException exception) {
                lastFailure = exception;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(exception)) {
                    break;
                }
            }
        }
        throw new IllegalStateException(method + " " + path + " 失败: " + lastFailure.getMessage(), lastFailure);
    }

    private <T, R> R executeWithRetry(
            HttpMethod method, String path, Object body, ResponseHandler<R, T> handler, Class<T> responseType) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<T> response = restTemplate.exchange(url(path), method, entity(body), responseType);
                return handler.handle(response);
            } catch (RestClientException exception) {
                lastFailure = exception;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(exception)) {
                    break;
                }
            }
        }
        throw new IllegalStateException(method + " " + path + " 失败: " + lastFailure.getMessage(), lastFailure);
    }

    private HttpEntity<Object> entity(Object body) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.hasText(serviceAuthToken)) {
            headers.set(SERVICE_AUTH_HEADER, serviceAuthToken);
        }
        return headers;
    }

    private String url(String path) {
        return baseUrl + path;
    }

    private static boolean isExpectedStatus(int actual, int... expectedStatuses) {
        for (int expected : expectedStatuses) {
            if (expected == actual) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException httpError(HttpMethod method, String path, int status, Object body) {
        String hint = status == 401
                ? "（请检查 dg-web data-generator.service-auth.token 与 dg-ai service-auth-token 是否一致）"
                : "";
        return new IllegalStateException(
                method + " " + path + " 失败: HTTP " + status + hint + " " + body);
    }

    private static boolean isRetryable(RestClientException exception) {
        String message = exception.getMessage();
        if (message == null && exception.getCause() != null) {
            message = exception.getCause().getMessage();
        }
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("broken pipe");
    }

    private static String normalizeBaseUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Tool 服务 base-url 未配置");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @FunctionalInterface
    private interface ResponseHandler<R, T> {
        R handle(ResponseEntity<T> response);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobDefinitionDto(String fileName, String name, String id, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScheduleDto(boolean enabled, String cron, boolean editable, String nextRunAt) {
    }

    private record ContentRequest(String content) {
    }

    private record CreateRequest(String name, String displayName, String content) {
    }

    private record UpdateRequest(String displayName, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SchemaDto(
            String table,
            String constraints,
            Map<String, Object> seed,
            List<SchemaFieldDto> fields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SchemaFieldDto(
            String name,
            String type,
            boolean primaryKey,
            Map<String, Object> generator) {
    }

    private record PreviewRequest(String jobConfig, PreviewOptions preview) {
    }

    private record PreviewOptions(int limit, List<String> tables) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PreviewDto(String status, String duration, List<PreviewTableDto> tables) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PreviewTableDto(String tableName, List<Map<String, Object>> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobListDto(List<JobSummaryDto> items, long total, int page, int size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobSummaryDto(String jobId, String status, String submittedAt) {
    }

    private record SubmitRequest(String jobConfig) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobResponseDto(
            String jobId,
            String status,
            String duration,
            String errorMessage,
            JobProgressDto progress) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobProgressDto(
            int totalTables,
            int completedTables,
            long totalRows,
            long writtenRows,
            long failedRows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobLogDto(String timestamp, String level, String message) {
    }
}
