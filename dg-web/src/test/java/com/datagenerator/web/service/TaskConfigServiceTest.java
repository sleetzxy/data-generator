package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.web.dto.TaskConfigListResponse;
import com.datagenerator.web.dto.TaskConfigRequest;
import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.SqliteTestSupport;
import com.datagenerator.web.storage.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskConfigServiceTest {

    /** 满足生成配置校验的最小合法 YAML（不含 id/name/schedule 元数据） */
    private static final String VALID_YAML = """
            tables:
              - name: customers
                count: 10
            """;

    @TempDir
    Path tempDir;

    private TaskRepository taskRepository;
    private TaskConfigService service;
    private TaskScheduleManager scheduleManager;
    private TaskRunQueueExecutor scheduleExecutor;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        taskRepository = new TaskRepository(jdbcTemplate);
        ConfigPathResolver resolver = ConfigPathResolver.fromSetting(
                "classpath:configs", getClass().getClassLoader(), tempDir);
        scheduleManager = mock(TaskScheduleManager.class);
        scheduleExecutor = mock(TaskRunQueueExecutor.class);
        service = new TaskConfigService(
                resolver, taskRepository, new TaskScheduleService(taskRepository),
                scheduleManager, scheduleExecutor);
    }

    @Test
    void list_withTasks_returnsPageOrderedByCreatedAt() {
        taskRepository.insert(record("old_task", "旧任务", "2026-09-01T08:00:00Z"));
        taskRepository.insert(record("mid_task", "中间任务", "2026-09-01T12:00:00Z"));
        taskRepository.insert(record("new_task", "新任务", "2026-09-02T08:00:00Z"));

        TaskConfigListResponse page = service.list(null, 1, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.items()).extracting(TaskConfigResponse::getFileName)
                .containsExactly("new_task", "mid_task");
    }

    @Test
    void list_withNameKeyword_filtersByDisplayName() {
        taskRepository.insert(record("alpha", "Alpha 任务", "2026-09-01T08:00:00Z"));
        taskRepository.insert(record("beta", "Beta 演示", "2026-09-02T08:00:00Z"));

        TaskConfigListResponse page = service.list("演示", null, null);

        assertThat(page.items()).extracting(TaskConfigResponse::getFileName)
                .containsExactly("beta");
    }

    @Test
    void get_existing_returnsDisplayNameAndContent() throws IOException {
        taskRepository.insert(record("demo", "演示任务", "2026-09-01T08:00:00Z"));
        writeConfigFile("demo", """
                name: YAML 名称
                tables:
                  - name: customers
                    count: 10
                """);

        TaskConfigResponse response = service.get("demo");

        assertThat(response.getFileName()).isEqualTo("demo");
        // 显示名以主表为准，不读 YAML 的 name
        assertThat(response.getName()).isEqualTo("演示任务");
        assertThat(response.getId()).isEqualTo("demo");
        assertThat(response.getContent()).contains("name: YAML 名称");
    }

    @Test
    void get_missingFile_throwsNotFound() {
        taskRepository.insert(record("ghost", "幽灵任务", "2026-09-01T08:00:00Z"));

        assertThatThrownBy(() -> service.get("ghost"))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("file missing");
    }

    @Test
    void get_missingRow_throwsNotFound() {
        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void create_validRequest_writesFileAndInsertsRow() throws IOException {
        TaskConfigResponse created = service.create(request("demo_job", "演示任务", VALID_YAML));

        assertThat(created.getId()).matches("task[a-f0-9]{8}");
        assertThat(created.getFileName()).isEqualTo("demo_job");
        assertThat(created.getName()).isEqualTo("演示任务");

        TaskRepository.TaskRecord row = taskRepository.findByFileName("demo_job").orElseThrow();
        assertThat(row.id()).isEqualTo(created.getId());
        assertThat(row.displayName()).isEqualTo("演示任务");
        assertThat(row.scheduleEnabled()).isFalse();
        assertThat(row.createdAt()).isNotNull();

        String stored = Files.readString(tempDir.resolve("task-configs/demo_job.yaml"));
        assertThat(stored).doesNotMatch("(?m)^id:\\s");
        assertThat(stored).doesNotMatch("(?m)^name:\\s");
        assertThat(stored).doesNotMatch("(?m)^schedule:\\s");
        assertThat(stored).contains("customers");
    }

    @Test
    void create_yamlWithIdAndName_stripsThem() throws IOException {
        TaskConfigResponse created = service.create(request("demo_job", "请求显示名", """
                id: user_supplied
                name: YAML 显示名
                tables:
                  - name: customers
                    count: 10
                """));

        assertThat(created.getId()).matches("task[a-f0-9]{8}");
        assertThat(created.getName()).isEqualTo("请求显示名");

        String stored = Files.readString(tempDir.resolve("task-configs/demo_job.yaml"));
        assertThat(stored).doesNotMatch("(?m)^id:\\s");
        assertThat(stored).doesNotMatch("(?m)^name:\\s");
    }

    @Test
    void create_yamlWithScheduleBlock_rejects() {
        String yamlWithSchedule = """
                schedule:
                  enabled: true
                  cron: "0 0 2 * * ?"
                tables:
                  - name: customers
                    count: 10
                """;

        assertThatThrownBy(() -> service.create(request("demo_job", "演示任务", yamlWithSchedule)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schedule");

        assertThat(taskRepository.findByFileName("demo_job")).isEmpty();
        assertThat(tempDir.resolve("task-configs/demo_job.yaml")).doesNotExist();
    }

    @Test
    void create_withScheduleRequest_persistsScheduleFields() {
        TaskConfigRequest request = request("scheduled_task", "定时任务", VALID_YAML);
        TaskScheduleRequest schedule = new TaskScheduleRequest();
        schedule.setEnabled(true);
        schedule.setCron("0 0 2 * * ?");
        request.setSchedule(schedule);

        TaskConfigResponse created = service.create(request);

        TaskRepository.TaskRecord row = taskRepository.findByFileName("scheduled_task").orElseThrow();
        assertThat(row.scheduleEnabled()).isTrue();
        assertThat(row.scheduleCron()).isEqualTo("0 0 2 * * ?");
        assertThat(created.getSchedule()).isNotNull();
        assertThat(created.getSchedule().isEnabled()).isTrue();
        assertThat(created.getSchedule().getCron()).isEqualTo("0 0 2 * * ?");
    }

    @Test
    void create_duplicateFileName_rejects() {
        service.create(request("dup", "任务一", VALID_YAML));

        assertThatThrownBy(() -> service.create(request("dup", "任务二", VALID_YAML)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void create_withoutFileName_usesGeneratedIdAsFileName() {
        TaskConfigResponse created = service.create(request(null, "我的任务", VALID_YAML));

        assertThat(created.getFileName()).isEqualTo(created.getId());
        assertThat(created.getFileName()).matches("task[a-f0-9]{8}");
    }

    @Test
    void create_withNonAsciiFileName_rejects() {
        assertThatThrownBy(() -> service.create(request("中文文件名", "演示任务", VALID_YAML)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASCII");
    }

    @Test
    void create_enabledScheduleWithoutCron_doesNotWriteFileOrRow() {
        TaskConfigRequest request = request("orphan_job", "孤儿任务", VALID_YAML);
        TaskScheduleRequest schedule = new TaskScheduleRequest();
        schedule.setEnabled(true);
        request.setSchedule(schedule);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cron");

        assertThat(taskRepository.findByFileName("orphan_job")).isEmpty();
        assertThat(tempDir.resolve("task-configs/orphan_job.yaml")).doesNotExist();
    }

    @Test
    void update_existing_updatesDisplayNameAndFile() throws IOException {
        service.create(request("demo_job", "演示任务", VALID_YAML));

        service.update("demo_job", request("demo_job", "更新后的任务", """
                tables:
                  - name: orders
                    count: 5
                """));

        TaskRepository.TaskRecord row = taskRepository.findByFileName("demo_job").orElseThrow();
        assertThat(row.displayName()).isEqualTo("更新后的任务");
        assertThat(row.updatedAt()).isNotNull();
        String stored = Files.readString(tempDir.resolve("task-configs/demo_job.yaml"));
        assertThat(stored).contains("orders");
    }

    @Test
    void update_missing_throwsNotFound() {
        assertThatThrownBy(() -> service.update("nope", request("nope", "演示任务", VALID_YAML)))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void delete_existing_removesRowAndFile() {
        service.create(request("demo_job", "演示任务", VALID_YAML));

        service.delete("demo_job");

        assertThat(taskRepository.findByFileName("demo_job")).isEmpty();
        assertThat(tempDir.resolve("task-configs/demo_job.yaml")).doesNotExist();
        verify(scheduleManager).cancel("task-configs/demo_job.yaml");
        verify(scheduleExecutor).clearQueue("task-configs/demo_job.yaml");
    }

    @Test
    void delete_missingFile_stillRemovesRow() {
        taskRepository.insert(record("zombie", "僵尸任务", "2026-09-01T08:00:00Z"));

        service.delete("zombie");

        assertThat(taskRepository.findByFileName("zombie")).isEmpty();
        verify(scheduleManager).cancel("task-configs/zombie.yaml");
        verify(scheduleExecutor).clearQueue("task-configs/zombie.yaml");
    }

    @Test
    void delete_missing_throwsNotFound() {
        assertThatThrownBy(() -> service.delete("nope"))
                .isInstanceOf(TaskConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    private void writeConfigFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve("task-configs/" + fileName + ".yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static TaskRepository.TaskRecord record(String fileName, String displayName, String createdAt) {
        return new TaskRepository.TaskRecord(
                fileName, fileName, displayName, false, null, createdAt, null);
    }

    private static TaskConfigRequest request(String fileName, String displayName, String content) {
        TaskConfigRequest request = new TaskConfigRequest();
        if (fileName != null) {
            request.setFileName(fileName);
        }
        request.setDisplayName(displayName);
        request.setContent(content);
        return request;
    }
}
