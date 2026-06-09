package com.datagenerator.web.storage;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.JobLogEntry;
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
public class JobLogFileRepository {

    private static final String SEPARATOR = " | ";

    private final Path logDir;

    public JobLogFileRepository(DataGeneratorProperties properties) {
        this.logDir = Path.of(properties.getStorage().getLogDir()).toAbsolutePath().normalize();
    }

    public void append(String jobId, String level, String message) {
        try {
            Files.createDirectories(logDir);
            Path logFile = logFile(jobId);
            String line = Instant.now() + SEPARATOR + level + SEPARATOR + message + System.lineSeparator();
            Files.writeString(
                    logFile,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write job log file for " + jobId, exception);
        }
    }

    public List<JobLogEntry> findByJobId(String jobId) {
        Path logFile = logFile(jobId);
        if (!Files.exists(logFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            List<JobLogEntry> entries = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                entries.add(parseLine(line));
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read job log file for " + jobId, exception);
        }
    }

    public void deleteByJobId(String jobId) {
        try {
            Files.deleteIfExists(logFile(jobId));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete job log file for " + jobId, exception);
        }
    }

    private Path logFile(String jobId) {
        String safeName = jobId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return logDir.resolve(safeName + ".log");
    }

    static JobLogEntry parseLine(String line) {
        int firstSep = line.indexOf(SEPARATOR);
        int secondSep = firstSep < 0 ? -1 : line.indexOf(SEPARATOR, firstSep + SEPARATOR.length());
        if (firstSep < 0 || secondSep < 0) {
            return new JobLogEntry("", "INFO", line);
        }
        String timestamp = line.substring(0, firstSep);
        String level = line.substring(firstSep + SEPARATOR.length(), secondSep);
        String message = line.substring(secondSep + SEPARATOR.length());
        return new JobLogEntry(timestamp, level, message);
    }
}
