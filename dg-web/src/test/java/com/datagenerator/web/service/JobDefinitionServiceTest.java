package com.datagenerator.web.service;

import com.datagenerator.core.schema.ConfigPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDefinitionServiceTest {

    @TempDir
    Path tempDir;

    private JobDefinitionService service;

    @BeforeEach
    void setUp() {
        ConfigPathResolver resolver = ConfigPathResolver.forConfigDir(tempDir).withWritableOverlay(tempDir);
        service = new JobDefinitionService(resolver);
    }

    @Test
    void createAndGet_persistsDefinition() {
        var created = service.create(request("demo_job", "id: demo_job\nname: 演示任务\ntables: []"));

        assertThat(created.getName()).isEqualTo("demo_job");
        assertThat(created.getId()).isEqualTo("demo_job");
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
    void delete_customDefinition_removesFile() {
        service.create(request("demo_job", "id: demo_job\nname: 演示任务\ntables: []"));

        service.delete("demo_job");

        assertThatThrownBy(() -> service.get("demo_job"))
                .isInstanceOf(com.datagenerator.core.schema.ConfigLoadException.class);
    }

    @Test
    void list_returnsCreatedDefinitionsWithId() throws Exception {
        Files.createDirectories(tempDir.resolve("jobs"));
        Files.writeString(tempDir.resolve("jobs/alpha.yaml"), "id: alpha\nname: Alpha 任务\ntables: []");

        assertThat(service.list())
                .extracting("name", "id")
                .containsExactly(org.assertj.core.api.Assertions.tuple("alpha", "alpha"));
    }

    @Test
    void create_withoutId_rejectsRequest() {
        assertThatThrownBy(() -> service.create(request("demo_job", "name: 演示任务\ntables: []")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id field is required");
    }

    @Test
    void create_duplicateId_rejectsRequest() throws Exception {
        Files.createDirectories(tempDir.resolve("jobs"));
        Files.writeString(tempDir.resolve("jobs/existing.yaml"), "id: shared_id\nname: 已有任务\ntables: []");

        assertThatThrownBy(() -> service.create(request("new_job", "id: shared_id\nname: 新任务\ntables: []")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Job id already exists");
    }

    private static com.datagenerator.web.dto.JobDefinitionRequest request(String name, String content) {
        var request = new com.datagenerator.web.dto.JobDefinitionRequest();
        request.setName(name);
        request.setContent(content);
        return request;
    }
}
