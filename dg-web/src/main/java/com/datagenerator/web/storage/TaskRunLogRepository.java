package com.datagenerator.web.storage;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.TaskRunLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class TaskRunLogRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskRunLogRepository.class);
    private static final String SEPARATOR = " | ";

    private final Path logDir;

    public TaskRunLogRepository(DataGeneratorProperties properties) {
        this.logDir = Path.of(properties.getStorage().getLogDir()).toAbsolutePath().normalize();
    }

    public void append(String runId, String level, String message) {
        try {
            Files.createDirectories(logDir);
            Path logFile = logFile(runId);
            String line = Instant.now() + SEPARATOR + level + SEPARATOR + message + System.lineSeparator();
            Files.writeString(
                    logFile,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            log.error("写入任务日志失败 runId={} message={}", runId, message, exception);
        }
    }

    public void info(String runId, String message) {
        append(runId, "INFO", message);
    }

    public void warn(String runId, String message) {
        append(runId, "WARN", message);
    }

    public void error(String runId, String message) {
        append(runId, "ERROR", message);
    }

    public List<TaskRunLogEntry> getLogs(String runId) {
        return findByRunId(runId);
    }

    public void remove(String runId) {
        deleteByRunId(runId);
    }

    public List<TaskRunLogEntry> findByRunId(String runId) {
        Path logFile = logFile(runId);
        if (!Files.exists(logFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            List<TaskRunLogEntry> entries = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                entries.add(parseLine(line));
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read task run log file for " + runId, exception);
        }
    }

    public void deleteByRunId(String runId) {
        try {
            Files.deleteIfExists(logFile(runId));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete task run log file for " + runId, exception);
        }
    }

    private Path logFile(String runId) {
        String safeName = runId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return logDir.resolve(safeName + ".log");
    }

    static TaskRunLogEntry parseLine(String line) {
        int firstSep = line.indexOf(SEPARATOR);
        int secondSep = firstSep < 0 ? -1 : line.indexOf(SEPARATOR, firstSep + SEPARATOR.length());
        if (firstSep < 0 || secondSep < 0) {
            return new TaskRunLogEntry("", "INFO", line);
        }
        String timestamp = line.substring(0, firstSep);
        String level = line.substring(firstSep + SEPARATOR.length(), secondSep);
        String message = line.substring(secondSep + SEPARATOR.length());
        return new TaskRunLogEntry(timestamp, level, message);
    }
}
