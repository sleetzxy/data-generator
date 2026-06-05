package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogRepositoryTest {

    private JobLogRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new JobLogRepository(jdbcTemplate);
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
