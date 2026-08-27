package com.datagenerator.web.storage;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.TaskRunLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunLogRepositoryTest {

    @TempDir
    Path tempDir;

    private TaskRunLogRepository repository;

    @BeforeEach
    void setUp() {
        DataGeneratorProperties properties = new DataGeneratorProperties();
        properties.getStorage().setLogDir(tempDir.toString());
        repository = new TaskRunLogRepository(properties);
    }

    @Test
    void append_preservesInsertionOrder() {
        repository.append("job-1", "INFO", "first");
        repository.append("job-1", "WARN", "second");

        List<TaskRunLogEntry> logs = repository.findByRunId("job-1");
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getMessage()).isEqualTo("first");
        assertThat(logs.get(1).getLevel()).isEqualTo("WARN");
    }

    @Test
    void deleteByRunId_removesAllLogs() {
        repository.append("job-2", "INFO", "msg");
        repository.deleteByRunId("job-2");
        assertThat(repository.findByRunId("job-2")).isEmpty();
    }
}
