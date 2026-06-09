package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobLogEntry;
import com.datagenerator.web.storage.JobLogFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobLogStore {

    private static final Logger log = LoggerFactory.getLogger(JobLogStore.class);

    private final JobLogFileRepository jobLogFileRepository;

    public JobLogStore(JobLogFileRepository jobLogFileRepository) {
        this.jobLogFileRepository = jobLogFileRepository;
    }

    public void append(String jobId, String level, String message) {
        try {
            jobLogFileRepository.append(jobId, level, message);
        } catch (Exception exception) {
            log.error("Failed to append log for job {}: {}", jobId, message, exception);
        }
    }

    public void info(String jobId, String message) {
        append(jobId, "INFO", message);
    }

    public void warn(String jobId, String message) {
        append(jobId, "WARN", message);
    }

    public void error(String jobId, String message) {
        append(jobId, "ERROR", message);
    }

    public List<JobLogEntry> getLogs(String jobId) {
        return jobLogFileRepository.findByJobId(jobId);
    }

    public void remove(String jobId) {
        jobLogFileRepository.deleteByJobId(jobId);
    }
}
