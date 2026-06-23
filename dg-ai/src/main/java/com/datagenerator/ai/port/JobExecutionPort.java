package com.datagenerator.ai.port;

import java.util.List;
import java.util.Map;

public interface JobExecutionPort {

    JobListPage listJobs(int page, int size);

    JobStatus submitJob(String jobConfigYaml);

    JobStatus getJob(String jobId);

    List<JobLogEntry> getJobLogs(String jobId);

    void cancelJob(String jobId);

    record JobListPage(List<JobSummary> items, long total, int page, int size) {}

    record JobSummary(String jobId, String status, String submittedAt) {}

    record JobStatus(
            String jobId,
            String status,
            String duration,
            String errorMessage,
            Map<String, Object> progress) {}

    record JobLogEntry(String timestamp, String level, String message) {}
}
