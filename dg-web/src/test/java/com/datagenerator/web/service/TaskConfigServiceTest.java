package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.web.storage.TaskScheduleRepository;
import com.datagenerator.web.storage.SqliteTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskConfigServiceTest {

    @TempDir
    Path tempDir;

    private Path primaryDir;
    private Path overlayDir;
    private TaskConfigService service;
    private TaskScheduleManager scheduleManager;
    private TaskRunQueueExecutor scheduleExecutor;
    private TaskScheduleRepository scheduleRepository;

    @BeforeEach
    void setUp() throws Exception {
        primaryDir = tempDir.resolve("primary");
        overlayDir = tempDir.resolve("overlay");
        Files.createDirectories(primaryDir);
        Files.createDirectories(overlayDir);
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        scheduleRepository = new TaskScheduleRepository(jdbcTemplate);
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        TaskScheduleService scheduleService = new TaskScheduleService(resolver, scheduleRepository);
        scheduleManager = mock(TaskScheduleManager.class);
        scheduleExecutor = mock(TaskRunQueueExecutor.class);
        service = new TaskConfigService(
                resolver, scheduleService, scheduleManager, scheduleExecutor, scheduleRepository);
    }

    @Test
    void createAndGet_persistsDefinition() {
        var created = service.create(request("demo_job", "演示任务", "tables: []"));

        assertThat(created.getFileName()).isEqualTo("demo_job");
        assertThat(created.getName()).isEqualTo("演示任务");
        assertThat(created.getId()).matches("task[a-f0-9]{8}");
        assertThat(created.isBuiltin()).isFalse();
        assertThat(created.isReadOnly()).isFalse();

        var loaded = service.get("demo_job");
        assertThat(loaded.getContent()).contains("id: " + created.getId());
        assertThat(loaded.getContent()).doesNotMatch("(?m)^name:\\s");
    }

    @Test
    void update_existingDefinition_overwritesContentAndDisplayName() {
        var created = service.create(request("demo_job", "演示任务", "tables: []"));

        service.update("demo_job", request("demo_job", "更新后的任务", """
                id: %s
                tables:
                  - name: t1
                    count: 1
                """.formatted(created.getId())));

        assertThat(service.get("demo_job").getName()).isEqualTo("更新后的任务");
        assertThat(service.get("demo_job").getContent()).contains("count: 1");
    }

    @Test
    void update_builtinDefinition_rejectsModification() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(
                primaryDir.resolve("task-configs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        TaskConfigService builtinService = createService(resolver);

        assertThatThrownBy(() -> builtinService.update("builtin", request("builtin", "修改后", """
                id: builtin
                tables: []
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be modified");
    }

    @Test
    void list_builtinWithOverlayCopy_stillBuiltin() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.createDirectories(overlayDir.resolve("task-configs"));
        Files.writeString(
                primaryDir.resolve("task-configs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");
        Files.writeString(
                overlayDir.resolve("task-configs/builtin.yaml"),
                "id: builtin\nname: 覆盖副本\ntables: []");
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        TaskConfigService builtinService = createService(resolver);

        assertThat(builtinService.list().items())
                .extracting("fileName", "builtin", "readOnly")
                .containsExactly(org.assertj.core.api.Assertions.tuple("builtin", true, true));
    }

    @Test
    void delete_customDefinition_removesFile() {
        service.create(request("demo_job", "演示任务", "tables: []"));

        service.delete("demo_job");

        assertThatThrownBy(() -> service.get("demo_job"))
                .isInstanceOf(com.datagenerator.core.model.ConfigLoadException.class);
        verify(scheduleManager).cancel("task-configs/demo_job.yaml");
        verify(scheduleExecutor).clearQueue("task-configs/demo_job.yaml");
        assertThat(scheduleRepository.findByConfigPath("task-configs/demo_job.yaml")).isEmpty();
    }

    @Test
    void delete_builtinDefinition_rejectsDeletion() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(
                primaryDir.resolve("task-configs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        TaskConfigService builtinService = createService(resolver);

        assertThatThrownBy(() -> builtinService.delete("builtin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void list_filterByNameKeyword_returnsMatchingDefinitions() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(primaryDir.resolve("task-configs/alpha.yaml"), "id: alpha\nname: Alpha 任务\ntables: []");
        Files.writeString(primaryDir.resolve("task-configs/beta.yaml"), "id: beta\nname: Beta 演示\ntables: []");

        assertThat(service.list("alpha").items())
                .extracting("fileName", "name")
                .containsExactly(org.assertj.core.api.Assertions.tuple("alpha", "Alpha 任务"));
    }

    @Test
    void list_filterByNameKeyword_caseInsensitive() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(primaryDir.resolve("task-configs/alpha.yaml"), "id: alpha\nname: Alpha 任务\ntables: []");

        assertThat(service.list("ALPHA").items())
                .extracting("fileName")
                .containsExactly("alpha");
    }

    @Test
    void list_filterByNameKeyword_blankReturnsAll() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(primaryDir.resolve("task-configs/alpha.yaml"), "id: alpha\nname: Alpha 任务\ntables: []");
        Files.writeString(primaryDir.resolve("task-configs/beta.yaml"), "id: beta\nname: Beta 演示\ntables: []");

        assertThat(service.list("   ").items())
                .extracting("fileName")
                .containsExactly("alpha", "beta");
    }

    @Test
    void list_returnsYamlNameAndFileName() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(primaryDir.resolve("task-configs/alpha.yaml"), "id: alpha\nname: Alpha 任务\ntables: []");

        assertThat(service.list().items())
                .extracting("fileName", "name", "id", "builtin")
                .containsExactly(org.assertj.core.api.Assertions.tuple("alpha", "Alpha 任务", "alpha", true));
    }

    @Test
    void list_nestedBuiltinJob_excludedFromList() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs/nested"));
        Files.writeString(
                primaryDir.resolve("task-configs/top.yaml"),
                """
                id: top
                name: 顶层任务
                tables:
                  - name: t1
                    count: 1
                """);
        Files.writeString(
                primaryDir.resolve("task-configs/nested/hidden.yaml"),
                """
                id: hidden
                name: 子目录任务
                tables:
                  - name: t1
                    count: 1
                """);

        assertThat(service.list().items())
                .extracting("fileName", "id")
                .containsExactly(org.assertj.core.api.Assertions.tuple("top", "top"));
    }

    @Test
    void list_updateDoesNotChangeCustomSortOrder() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(
                primaryDir.resolve("task-configs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");

        service.create(request("older_job", "旧任务", "tables: []"));
        Thread.sleep(20);
        service.create(request("newer_job", "新任务", "tables: []"));

        var created = service.get("older_job");
        service.update("older_job", request("older_job", "旧任务已更新", """
                id: %s
                tables:
                  - name: t1
                    count: 1
                """.formatted(created.getId())));
        Thread.sleep(20);

        assertThat(service.list().items())
                .extracting("fileName")
                .containsExactly("builtin", "newer_job", "older_job");
    }

    @Test
    void list_builtinFirst_customSortedByCreatedAtDesc() throws Exception {
        Files.createDirectories(primaryDir.resolve("task-configs"));
        Files.writeString(
                primaryDir.resolve("task-configs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");

        service.create(request("older_job", "旧任务", "tables: []"));
        Thread.sleep(20);
        service.create(request("newer_job", "新任务", "tables: []"));

        assertThat(service.list().items())
                .extracting("fileName")
                .containsExactly("builtin", "newer_job", "older_job");
    }

    @Test
    void create_withoutId_generatesUniqueId() {
        var created = service.create(request("demo_job", "演示任务", "tables: []"));

        assertThat(created.getId()).matches("task[a-f0-9]{8}");
        assertThat(created.getContent()).contains("id: " + created.getId());
    }

    @Test
    void create_ignoresProvidedId_generatesNewId() {
        var created = service.create(request("demo_job", "演示任务", "id: user_id\ntables: []"));

        assertThat(created.getId()).isNotEqualTo("user_id");
        assertThat(created.getId()).matches("task[a-f0-9]{8}");
    }

    @Test
    void create_customJobWithScheduleBlock_rejected() {
        assertThatThrownBy(() -> service.create(request("demo_job", "演示任务", """
                id: demo_job
                schedule:
                  enabled: true
                  cron: "0 0 2 * * ?"
                tables: []
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain schedule block");
    }

    @Test
    void create_generatesUniqueIdsForEachJob() {
        var first = service.create(request("job_a", "任务 A", "tables: []"));
        var second = service.create(request("job_b", "任务 B", "tables: []"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    private TaskConfigService createService(ConfigPathResolver resolver) {
        TaskScheduleService scheduleService = new TaskScheduleService(resolver, scheduleRepository);
        return new TaskConfigService(
                resolver,
                scheduleService,
                mock(TaskScheduleManager.class),
                mock(TaskRunQueueExecutor.class),
                scheduleRepository);
    }

    @Test
    void create_enabledScheduleWithoutCron_doesNotPersistDefinition() {
        assertThatThrownBy(() -> service.create(requestWithSchedule(
                        "orphan_job",
                        "孤儿任务",
                        "tables: []",
                        true,
                        "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cron");

        assertThatThrownBy(() -> service.get("orphan_job"))
                .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    void create_withValidSchedule_persistsDefinitionAndSchedule() {
        var created = service.create(requestWithSchedule(
                "scheduled_task",
                "定时任务",
                "tables: []",
                true,
                "0 0 2 * * ?"));

        assertThat(created.getSchedule()).isNotNull();
        assertThat(created.getSchedule().isEnabled()).isTrue();
        assertThat(created.getSchedule().getCron()).isEqualTo("0 0 2 * * ?");
        assertThat(scheduleRepository.findByConfigPath("task-configs/scheduled_task.yaml")).isPresent();
    }

    private static com.datagenerator.web.dto.TaskConfigRequest requestWithSchedule(
            String name, String displayName, String content, boolean enabled, String cron) {
        var request = request(name, displayName, content);
        var schedule = new com.datagenerator.web.dto.TaskScheduleRequest();
        schedule.setEnabled(enabled);
        schedule.setCron(cron);
        request.setSchedule(schedule);
        return request;
    }

    @Test
    void create_withNonAsciiExplicitFileName_rejected() {
        assertThatThrownBy(() -> service.create(request("中文文件名", "演示任务", "tables: []")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASCII");
    }

    @Test
    void create_withoutExplicitFileName_usesGeneratedIdAsFileName() {
        var created = service.create(request(null, "我的测试任务", "tables: []"));

        assertThat(created.getFileName()).isEqualTo(created.getId());
        assertThat(created.getFileName()).matches("task[a-f0-9]{8}");
        assertThat(created.getName()).isEqualTo("我的测试任务");
    }

    private static com.datagenerator.web.dto.TaskConfigRequest request(
            String name, String displayName, String content) {
        var request = new com.datagenerator.web.dto.TaskConfigRequest();
        if (name != null) {
            request.setName(name);
        }
        request.setDisplayName(displayName);
        request.setContent(content);
        return request;
    }
}
