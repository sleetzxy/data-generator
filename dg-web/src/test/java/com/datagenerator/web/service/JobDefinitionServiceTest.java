package com.datagenerator.web.service;

import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.web.storage.JobScheduleRepository;
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

class JobDefinitionServiceTest {

    @TempDir
    Path tempDir;

    private Path primaryDir;
    private Path overlayDir;
    private JobDefinitionService service;
    private JobScheduleManager scheduleManager;
    private JobScheduleExecutor scheduleExecutor;
    private JobScheduleRepository scheduleRepository;

    @BeforeEach
    void setUp() throws Exception {
        primaryDir = tempDir.resolve("primary");
        overlayDir = tempDir.resolve("overlay");
        Files.createDirectories(primaryDir);
        Files.createDirectories(overlayDir);
        JdbcTemplate jdbcTemplate = SqliteTestSupport.createInMemoryJdbcTemplate();
        scheduleRepository = new JobScheduleRepository(jdbcTemplate);
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        JobScheduleService scheduleService = new JobScheduleService(resolver, scheduleRepository);
        scheduleManager = mock(JobScheduleManager.class);
        scheduleExecutor = mock(JobScheduleExecutor.class);
        service = new JobDefinitionService(
                resolver, scheduleService, scheduleManager, scheduleExecutor, scheduleRepository);
    }

    @Test
    void createAndGet_persistsDefinition() {
        var created = service.create(request("demo_job", "演示任务", "tables: []"));

        assertThat(created.getFileName()).isEqualTo("demo_job");
        assertThat(created.getName()).isEqualTo("演示任务");
        assertThat(created.getId()).matches("job[a-f0-9]{8}");
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
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(
                primaryDir.resolve("jobs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        JobDefinitionService builtinService = createService(resolver);

        assertThatThrownBy(() -> builtinService.update("builtin", request("builtin", "修改后", """
                id: builtin
                tables: []
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be modified");
    }

    @Test
    void list_builtinWithOverlayCopy_stillBuiltin() throws Exception {
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.createDirectories(overlayDir.resolve("jobs"));
        Files.writeString(
                primaryDir.resolve("jobs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");
        Files.writeString(
                overlayDir.resolve("jobs/builtin.yaml"),
                "id: builtin\nname: 覆盖副本\ntables: []");
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        JobDefinitionService builtinService = createService(resolver);

        assertThat(builtinService.list())
                .extracting("fileName", "builtin", "readOnly")
                .containsExactly(org.assertj.core.api.Assertions.tuple("builtin", true, true));
    }

    @Test
    void delete_customDefinition_removesFile() {
        service.create(request("demo_job", "演示任务", "tables: []"));

        service.delete("demo_job");

        assertThatThrownBy(() -> service.get("demo_job"))
                .isInstanceOf(com.datagenerator.core.schema.ConfigLoadException.class);
        verify(scheduleManager).cancel("jobs/demo_job.yaml");
        verify(scheduleExecutor).clearQueue("jobs/demo_job.yaml");
        assertThat(scheduleRepository.findByConfigPath("jobs/demo_job.yaml")).isEmpty();
    }

    @Test
    void delete_builtinDefinition_rejectsDeletion() throws Exception {
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(
                primaryDir.resolve("jobs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(primaryDir).withWritableOverlay(overlayDir);
        JobDefinitionService builtinService = createService(resolver);

        assertThatThrownBy(() -> builtinService.delete("builtin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void list_returnsYamlNameAndFileName() throws Exception {
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(primaryDir.resolve("jobs/alpha.yaml"), "id: alpha\nname: Alpha 任务\ntables: []");

        assertThat(service.list())
                .extracting("fileName", "name", "id", "builtin")
                .containsExactly(org.assertj.core.api.Assertions.tuple("alpha", "Alpha 任务", "alpha", true));
    }

    @Test
    void list_builtinFirst_customSortedByCreatedAtDesc() throws Exception {
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(
                primaryDir.resolve("jobs/builtin.yaml"),
                "id: builtin\nname: 内置任务\ntables: []");

        service.create(request("older_job", "旧任务", "tables: []"));
        Thread.sleep(20);
        service.create(request("newer_job", "新任务", "tables: []"));

        assertThat(service.list())
                .extracting("fileName")
                .containsExactly("builtin", "newer_job", "older_job");
    }

    @Test
    void create_withoutId_generatesUniqueId() {
        var created = service.create(request("demo_job", "演示任务", "tables: []"));

        assertThat(created.getId()).matches("job[a-f0-9]{8}");
        assertThat(created.getContent()).contains("id: " + created.getId());
    }

    @Test
    void create_ignoresProvidedId_generatesNewId() {
        var created = service.create(request("demo_job", "演示任务", "id: user_id\ntables: []"));

        assertThat(created.getId()).isNotEqualTo("user_id");
        assertThat(created.getId()).matches("job[a-f0-9]{8}");
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

    private JobDefinitionService createService(ConfigPathResolver resolver) {
        JobScheduleService scheduleService = new JobScheduleService(resolver, scheduleRepository);
        return new JobDefinitionService(
                resolver,
                scheduleService,
                mock(JobScheduleManager.class),
                mock(JobScheduleExecutor.class),
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
                "scheduled_job",
                "定时任务",
                "tables: []",
                true,
                "0 0 2 * * ?"));

        assertThat(created.getSchedule()).isNotNull();
        assertThat(created.getSchedule().isEnabled()).isTrue();
        assertThat(created.getSchedule().getCron()).isEqualTo("0 0 2 * * ?");
        assertThat(scheduleRepository.findByConfigPath("jobs/scheduled_job.yaml")).isPresent();
    }

    private static com.datagenerator.web.dto.JobDefinitionRequest requestWithSchedule(
            String name, String displayName, String content, boolean enabled, String cron) {
        var request = request(name, displayName, content);
        var schedule = new com.datagenerator.web.dto.JobScheduleRequest();
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
        assertThat(created.getFileName()).matches("job[a-f0-9]{8}");
        assertThat(created.getName()).isEqualTo("我的测试任务");
    }

    private static com.datagenerator.web.dto.JobDefinitionRequest request(
            String name, String displayName, String content) {
        var request = new com.datagenerator.web.dto.JobDefinitionRequest();
        if (name != null) {
            request.setName(name);
        }
        request.setDisplayName(displayName);
        request.setContent(content);
        return request;
    }
}
