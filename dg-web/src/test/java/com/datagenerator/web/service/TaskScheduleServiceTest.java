package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.SqliteTestSupport;
import com.datagenerator.web.storage.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskScheduleServiceTest {

    private TaskScheduleService service;
    private TaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        taskRepository = new TaskRepository(jdbcTemplate);
        service = new TaskScheduleService(taskRepository);
    }

    @Test
    void resolveSchedule_noRow_throws() {
        assertThatThrownBy(() -> service.resolveSchedule("task-configs/ghost.yaml"))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveSchedule_withRow_returnsEnabledCronAndNextRunAt() {
        insertTask("demo", true, "0 30 3 * * ?");

        TaskScheduleResponse response = service.resolveSchedule("task-configs/demo.yaml");

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getCron()).isEqualTo("0 30 3 * * ?");
        assertThat(response.getNextRunAt()).isNotNull();
    }

    @Test
    void saveSchedule_valid_updatesTasksRow() {
        insertTask("demo", false, null);

        TaskScheduleResponse saved =
                service.saveSchedule("task-configs/demo.yaml", scheduleRequest(true, "0 0 2 * * ?"));

        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCron()).isEqualTo("0 0 2 * * ?");
        TaskRepository.TaskRecord row = taskRepository.findByFileName("demo").orElseThrow();
        assertThat(row.scheduleEnabled()).isTrue();
        assertThat(row.scheduleCron()).isEqualTo("0 0 2 * * ?");
        assertThat(row.updatedAt()).isNotNull();
    }

    @Test
    void saveSchedule_enabledWithoutCron_rejects() {
        insertTask("demo", false, null);

        assertThatThrownBy(() -> service.saveSchedule("task-configs/demo.yaml", scheduleRequest(true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required when schedule is enabled");
    }

    @Test
    void saveSchedule_invalidCron_rejects() {
        insertTask("demo", false, null);

        assertThatThrownBy(() -> service.saveSchedule("task-configs/demo.yaml", scheduleRequest(true, "not-a-cron")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void saveSchedule_missingRow_throws() {
        assertThatThrownBy(() -> service.saveSchedule("task-configs/ghost.yaml", scheduleRequest(false, null)))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    private void insertTask(String fileName, boolean enabled, String cron) {
        taskRepository.insert(new TaskRepository.TaskRecord(
                fileName, fileName, "演示任务", enabled, cron, "2026-09-02T10:00:00Z", null));
    }

    private static TaskScheduleRequest scheduleRequest(boolean enabled, String cron) {
        TaskScheduleRequest request = new TaskScheduleRequest();
        request.setEnabled(enabled);
        request.setCron(cron);
        return request;
    }
}
