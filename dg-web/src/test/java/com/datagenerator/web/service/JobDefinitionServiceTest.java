package com.datagenerator.web.service;

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
        var created = service.create(request("demo_job", "id: demo_job\nname: 演示任务\ntables: []"));

        assertThat(created.getFileName()).isEqualTo("demo_job");
        assertThat(created.getName()).isEqualTo("演示任务");
        assertThat(created.getId()).isEqualTo("demo_job");
        assertThat(created.isBuiltin()).isFalse();
        assertThat(created.isReadOnly()).isFalse();

        var loaded = service.get("demo_job");
        assertThat(loaded.getContent()).contains("id: demo_job");
    }

    @Test
    void update_existingDefinition_overwritesContent() {
        service.create(request("demo_job", "id: demo_job\nname: 演示任务\ntables: []"));

        service.update("demo_job", request("demo_job", """
                id: demo_job
                name: 演示任务
                tables:
                  - name: t1
                    count: 1
                """));

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

        assertThatThrownBy(() -> builtinService.update("builtin", request("builtin", """
                id: builtin
                name: 修改后
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
        service.create(request("demo_job", "id: demo_job\nname: 演示任务\ntables: []"));

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
    void create_withoutId_rejectsRequest() {
        assertThatThrownBy(() -> service.create(request("demo_job", "name: 演示任务\ntables: []")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id field is required");
    }

    @Test
    void create_customJobWithScheduleBlock_rejected() {
        assertThatThrownBy(() -> service.create(request("demo_job", """
                id: demo_job
                name: 演示任务
                schedule:
                  enabled: true
                  cron: "0 0 2 * * ?"
                tables: []
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain schedule block");
    }

    @Test
    void create_duplicateId_rejectsRequest() throws Exception {
        Files.createDirectories(primaryDir.resolve("jobs"));
        Files.writeString(primaryDir.resolve("jobs/existing.yaml"), "id: shared_id\nname: 已有任务\ntables: []");

        assertThatThrownBy(() -> service.create(request("new_job", "id: shared_id\nname: 新任务\ntables: []")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Job id already exists");
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

    private static com.datagenerator.web.dto.JobDefinitionRequest request(String name, String content) {
        var request = new com.datagenerator.web.dto.JobDefinitionRequest();
        request.setName(name);
        request.setContent(content);
        return request;
    }
}
