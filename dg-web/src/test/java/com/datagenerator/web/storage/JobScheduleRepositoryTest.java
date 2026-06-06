package com.datagenerator.web.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JobScheduleRepositoryTest {

    private JobScheduleRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new JobScheduleRepository(jdbcTemplate);
    }

    @Test
    void upsertAndFind_roundTrip() {
        repository.upsert("jobs/my.yaml", true, "0 0 2 * * ?", Instant.now().toString());
        Optional<JobScheduleRepository.JobScheduleRecord> found =
                repository.findByConfigPath("jobs/my.yaml");
        assertThat(found).isPresent();
        assertThat(found.get().enabled()).isTrue();
        assertThat(found.get().cron()).isEqualTo("0 0 2 * * ?");
    }

    @Test
    void deleteByConfigPath_removesRow() {
        repository.upsert("jobs/x.yaml", false, null, Instant.now().toString());
        repository.deleteByConfigPath("jobs/x.yaml");
        assertThat(repository.findByConfigPath("jobs/x.yaml")).isEmpty();
    }
}
