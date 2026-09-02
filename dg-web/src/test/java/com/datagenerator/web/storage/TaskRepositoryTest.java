package com.datagenerator.web.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRepositoryTest {

    private TaskRepository repository;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        repository = new TaskRepository(jdbcTemplate);
    }

    @Test
    void insert_and_findByFileName_returnsTask() {
        repository.insert(new TaskRepository.TaskRecord(
                "taskabc123", "taskabc123", "演示任务", false, null,
                "2026-09-02T10:00:00Z", null));

        var found = repository.findByFileName("taskabc123");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("taskabc123");
        assertThat(found.get().displayName()).isEqualTo("演示任务");
        assertThat(found.get().scheduleEnabled()).isFalse();
    }

    @Test
    void findById_returnsTask() {
        repository.insert(new TaskRepository.TaskRecord(
                "taskabc123", "taskabc123", "演示任务", false, null,
                "2026-09-02T10:00:00Z", null));

        var found = repository.findById("taskabc123");

        assertThat(found).isPresent();
        assertThat(found.get().fileName()).isEqualTo("taskabc123");
        assertThat(found.get().displayName()).isEqualTo("演示任务");
    }

    @Test
    void update_updatesDisplayNameAndTimestamp() {
        repository.insert(new TaskRepository.TaskRecord(
                "taskabc123", "taskabc123", "演示任务", false, null,
                "2026-09-02T10:00:00Z", null));

        repository.update("taskabc123", "改名任务", "2026-09-02T11:00:00Z");

        var found = repository.findByFileName("taskabc123").orElseThrow();
        assertThat(found.displayName()).isEqualTo("改名任务");
        assertThat(found.updatedAt()).isEqualTo("2026-09-02T11:00:00Z");
        assertThat(found.createdAt()).isEqualTo("2026-09-02T10:00:00Z");
    }

    @Test
    void updateSchedule_updatesScheduleFieldsOnly() {
        repository.insert(new TaskRepository.TaskRecord(
                "taskabc123", "taskabc123", "演示任务", false, null,
                "2026-09-02T10:00:00Z", null));

        repository.updateSchedule("taskabc123", true, "0 0 2 * * ?", "2026-09-02T11:00:00Z");

        var found = repository.findByFileName("taskabc123").orElseThrow();
        assertThat(found.scheduleEnabled()).isTrue();
        assertThat(found.scheduleCron()).isEqualTo("0 0 2 * * ?");
        assertThat(found.displayName()).isEqualTo("演示任务");
    }

    @Test
    void listPage_withKeywordAndPagination_returnsFilteredPage() {
        repository.insert(record("t1", "用户表任务"));
        repository.insert(record("t2", "订单表任务"));
        repository.insert(record("t3", "订单明细"));

        List<TaskRepository.TaskRecord> page = repository.listPage(0, 2, "订单");
        long total = repository.count("订单");

        assertThat(total).isEqualTo(2);
        assertThat(page).hasSize(2);
        assertThat(page).extracting(TaskRepository.TaskRecord::fileName)
                .containsExactlyInAnyOrder("t2", "t3");
    }

    @Test
    void listPage_withoutKeyword_ordersByCreatedAtDesc() {
        repository.insert(record("t1", "旧任务"));
        repository.insert(record("t2", "新任务"));

        List<TaskRepository.TaskRecord> page = repository.listPage(0, 10, null);

        assertThat(page).extracting(TaskRepository.TaskRecord::fileName)
                .containsExactly("t2", "t1");
    }

    @Test
    void findAllEnabledSchedules_returnsOnlyEnabledWithCron() {
        repository.insert(new TaskRepository.TaskRecord(
                "t1", "t1", "a", true, "0 0 1 * * ?", "2026-09-02T10:00:00Z", null));
        repository.insert(new TaskRepository.TaskRecord(
                "t2", "t2", "b", false, "0 0 2 * * ?", "2026-09-02T10:00:00Z", null));
        repository.insert(new TaskRepository.TaskRecord(
                "t3", "t3", "c", true, null, "2026-09-02T10:00:00Z", null));

        var schedules = repository.findAllEnabledSchedules();

        assertThat(schedules).extracting(TaskRepository.TaskRecord::fileName)
                .containsExactly("t1");
    }

    @Test
    void deleteByFileName_removesRow() {
        repository.insert(record("t1", "任务"));

        repository.deleteByFileName("t1");

        assertThat(repository.findByFileName("t1")).isEmpty();
    }

    @Test
    void existsByFileName_returnsTrueOnlyForExisting() {
        repository.insert(record("t1", "任务"));

        assertThat(repository.existsByFileName("t1")).isTrue();
        assertThat(repository.existsByFileName("t9")).isFalse();
    }

    private static TaskRepository.TaskRecord record(String fileName, String displayName) {
        return new TaskRepository.TaskRecord(
                fileName, fileName, displayName, false, null,
                "2026-09-02T10:00:00Z", null);
    }
}
