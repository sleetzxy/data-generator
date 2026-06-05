package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobLogEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class JobLogStore {

    private static final int MAX_ENTRIES = 500;

    private final ConcurrentHashMap<String, Deque<JobLogEntry>> logs = new ConcurrentHashMap<>();

    public void append(String jobId, String level, String message) {
        Deque<JobLogEntry> entries = logs.computeIfAbsent(jobId, ignored -> new ConcurrentLinkedDeque<>());
        entries.addLast(new JobLogEntry(Instant.now().toString(), level, message));
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
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
        Deque<JobLogEntry> entries = logs.get(jobId);
        if (entries == null) {
            return List.of();
        }
        return new ArrayList<>(entries);
    }

    public void remove(String jobId) {
        logs.remove(jobId);
    }
}
