package com.datagenerator.ai.port.http;

import com.datagenerator.ai.port.JobExecutionPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;

public class HttpJobExecutionPort implements JobExecutionPort {

    private static final String JOBS_PATH = "/api/v1/jobs";

    private final HttpServiceClient webClient;

    public HttpJobExecutionPort(HttpServiceClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public JobListPage listJobs(int page, int size) {
        JobListDto response = webClient.get(
                JOBS_PATH + "?page=" + page + "&size=" + size,
                new TypeReference<>() {});
        List<JobSummary> items = response.items() == null
                ? List.of()
                : response.items().stream()
                        .map(item -> new JobSummary(
                                item.jobId(),
                                item.status() == null ? null : item.status(),
                                item.submittedAt()))
                        .toList();
        return new JobListPage(items, response.total(), response.page(), response.size());
    }

    @Override
    public JobStatus submitJob(String jobConfigYaml) {
        JobResponseDto response = webClient.postAccept(
                JOBS_PATH,
                new SubmitRequest(jobConfigYaml),
                new TypeReference<>() {});
        return toStatus(response);
    }

    @Override
    public JobStatus getJob(String jobId) {
        return toStatus(webClient.get(JOBS_PATH + "/" + jobId, new TypeReference<>() {}));
    }

    @Override
    public List<JobLogEntry> getJobLogs(String jobId) {
        List<JobLogDto> logs = webClient.get(JOBS_PATH + "/" + jobId + "/logs", new TypeReference<>() {});
        return logs.stream()
                .map(log -> new JobLogEntry(log.timestamp(), log.level(), log.message()))
                .toList();
    }

    @Override
    public void cancelJob(String jobId) {
        webClient.delete(JOBS_PATH + "/" + jobId);
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

    private record SubmitRequest(String jobConfig) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobListDto(List<JobSummaryDto> items, long total, int page, int size) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobSummaryDto(String jobId, String status, String submittedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobResponseDto(
            String jobId,
            String status,
            String duration,
            String errorMessage,
            JobProgressDto progress) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobProgressDto(
            int totalTables,
            int completedTables,
            long totalRows,
            long writtenRows,
            long failedRows) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobLogDto(String timestamp, String level, String message) {}
}
