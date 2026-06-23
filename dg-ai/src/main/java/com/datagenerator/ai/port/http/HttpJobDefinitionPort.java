package com.datagenerator.ai.port.http;

import com.datagenerator.ai.port.JobDefinitionPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

public class HttpJobDefinitionPort implements JobDefinitionPort {

    private static final String JOBS_PATH = "/api/v1/job-definitions";

    private final HttpServiceClient webClient;

    public HttpJobDefinitionPort(HttpServiceClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public List<JobSummary> listJobs() {
        List<JobDefinitionDto> definitions = webClient.get(JOBS_PATH, new TypeReference<>() {});
        return definitions.stream()
                .map(item -> new JobSummary(item.id(), item.name(), item.fileName()))
                .toList();
    }

    @Override
    public JobDetail findJob(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        try {
            return toDetail(webClient.get(JOBS_PATH + "/" + fileName.trim(), new TypeReference<>() {}));
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    @Override
    public ValidationResult validateYaml(String yaml) {
        return webClient.post(
                JOBS_PATH + "?validateOnly=true",
                new ContentRequest(yaml),
                new TypeReference<>() {});
    }

    @Override
    public JobDetail createJob(CreateJobRequest request) {
        JobDefinitionDto created = webClient.postCreated(
                JOBS_PATH,
                new CreateRequest(request.name(), request.displayName(), request.content()),
                new TypeReference<>() {});
        return toDetail(created);
    }

    @Override
    public JobDetail updateJob(String fileName, UpdateJobRequest request) {
        JobDefinitionDto updated = webClient.put(
                JOBS_PATH + "/" + fileName.trim(),
                new UpdateRequest(request.displayName(), request.content()),
                new TypeReference<>() {});
        return toDetail(updated);
    }

    @Override
    public void deleteJob(String fileName) {
        webClient.delete(JOBS_PATH + "/" + fileName.trim());
    }

    @Override
    public JobSchedule getSchedule(String fileName) {
        ScheduleDto schedule = webClient.get(
                JOBS_PATH + "/" + fileName.trim() + "/schedule",
                new TypeReference<>() {});
        return new JobSchedule(schedule.enabled(), schedule.cron(), schedule.editable(), schedule.nextRunAt());
    }

    private JobDetail toDetail(JobDefinitionDto definition) {
        return new JobDetail(
                definition.id(),
                definition.name(),
                definition.fileName(),
                definition.content());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobDefinitionDto(String fileName, String name, String id, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScheduleDto(boolean enabled, String cron, boolean editable, String nextRunAt) {}

    private record ContentRequest(String content) {}

    private record CreateRequest(String name, String displayName, String content) {}

    private record UpdateRequest(String displayName, String content) {}
}
