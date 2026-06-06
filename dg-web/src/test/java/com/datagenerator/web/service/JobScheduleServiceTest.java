package com.datagenerator.web.service;

import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.web.dto.JobScheduleRequest;
import com.datagenerator.web.dto.JobScheduleResponse;
import com.datagenerator.web.storage.JobScheduleRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobScheduleServiceTest {

    @TempDir
    Path tempDir;

    private Path primaryDir;
    private Path overlayDir;
    private JobScheduleRepository scheduleRepository;
    private JobScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        primaryDir = tempDir.resolve("primary");
        overlayDir = tempDir.resolve("overlay");
        Files.createDirectories(primaryDir);
        Files.createDirectories(overlayDir);
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        scheduleRepository = new JobScheduleRepository(jdbcTemplate);
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        service = new JobScheduleService(resolver, scheduleRepository);
    }

    @Test
    void resolveSchedule_builtinJob_readsYaml() throws Exception {
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(
                primaryDir.resolve("jobs/scheduled.yaml"),
                """
                        id: scheduled
                        name: 定时任务
                        schedule:
                          enabled: true
                          cron: "0 0 2 * * ?"
                        tables: []
                        """);

        JobScheduleResponse response = service.resolveSchedule("jobs/scheduled.yaml", true);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getCron()).isEqualTo("0 0 2 * * ?");
        assertThat(response.isEditable()).isFalse();
        assertThat(response.getNextRunAt()).isNotNull();
    }

    @Test
    void resolveSchedule_customJob_readsSqlite() {
        scheduleRepository.upsert("jobs/custom.yaml", true, "0 30 3 * * ?", Instant.now().toString());

        JobScheduleResponse response = service.resolveSchedule("jobs/custom.yaml", false);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getCron()).isEqualTo("0 30 3 * * ?");
        assertThat(response.isEditable()).isTrue();
        assertThat(response.getNextRunAt()).isNotNull();
    }

    @Test
    void validateSchedule_enabledTrueRequiresValidCron() {
        JobScheduleRequest request = new JobScheduleRequest();
        request.setEnabled(true);
        request.setCron("not-a-cron");

        assertThatThrownBy(() -> service.validateAndNormalize(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");

        request.setCron(null);
        assertThatThrownBy(() -> service.validateAndNormalize(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required when schedule is enabled");
    }

    @Test
    void validateSchedule_enabledFalse_allowsEmptyCron() {
        JobScheduleRequest request = new JobScheduleRequest();
        request.setEnabled(false);
        request.setCron(null);

        JobScheduleRequest normalized = service.validateAndNormalize(request);

        assertThat(normalized.isEnabled()).isFalse();
        assertThat(normalized.getCron()).isNull();
    }

    @Test
    void computeNextRunAt_returnsIso8601WhenEnabled() {
        String nextRunAt = service.computeNextRunAt("0 0 2 * * ?");

        assertThat(nextRunAt).isNotNull();
        assertThat(OffsetDateTime.parse(nextRunAt)).isNotNull();
    }
}
