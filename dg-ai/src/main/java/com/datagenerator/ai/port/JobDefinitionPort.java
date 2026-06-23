package com.datagenerator.ai.port;

import java.util.List;

public interface JobDefinitionPort {

    List<JobSummary> listJobs();

    JobDetail findJob(String fileName);

    ValidationResult validateYaml(String yaml);

    JobDetail createJob(CreateJobRequest request);

    JobDetail updateJob(String fileName, UpdateJobRequest request);

    void deleteJob(String fileName);

    JobSchedule getSchedule(String fileName);

    record JobSummary(String id, String name, String fileName) {}

    record JobDetail(String id, String name, String fileName, String yaml) {}

    record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    record CreateJobRequest(String name, String displayName, String content) {}

    record UpdateJobRequest(String displayName, String content) {}

    record JobSchedule(boolean enabled, String cron, boolean editable, String nextRunAt) {}
}
