package com.datagenerator.web.storage;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.JobLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogFileRepositoryTest {

    @TempDir
    Path tempDir;

    private JobLogFileRepository repository;

    @BeforeEach
    void setUp() {
        DataGeneratorProperties properties = new DataGeneratorProperties();
        properties.getStorage().setLogDir(tempDir.toString());
        repository = new JobLogFileRepository(properties);
    }

    @Test
    void append_preservesInsertionOrder() {
        repository.append("job-1", "INFO", "first");
        repository.append("job-1", "WARN", "second");

        List<JobLogEntry> logs = repository.findByJobId("job-1");
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getMessage()).isEqualTo("first");
        assertThat(logs.get(1).getLevel()).isEqualTo("WARN");
    }

    @Test
    void deleteByJobId_removesAllLogs() {
        repository.append("job-2", "INFO", "msg");
        repository.deleteByJobId("job-2");
        assertThat(repository.findByJobId("job-2")).isEmpty();
    }
}
