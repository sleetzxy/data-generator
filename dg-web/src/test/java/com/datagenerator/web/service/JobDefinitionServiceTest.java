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
        var created = service.create(request("demo_job", "job: demo_job\ntables: []"));

        assertThat(created.getName()).isEqualTo("demo_job");
        assertThat(created.isReadOnly()).isFalse();

        var loaded = service.get("demo_job");
        assertThat(loaded.getContent()).contains("job: demo_job");
    }

    @Test
    void update_existingDefinition_overwritesContent() {
        service.create(request("demo_job", "job: demo_job\ntables: []"));

        service.update("demo_job", request("demo_job", "job: demo_job\ntables:\n  - name: t1\n    count: 1"));

        assertThat(service.get("demo_job").getContent()).contains("count: 1");
    }

    @Test
    void delete_customDefinition_removesFile() {
        service.create(request("demo_job", "job: demo_job\ntables: []"));

        service.delete("demo_job");

        assertThatThrownBy(() -> service.get("demo_job"))
                .isInstanceOf(com.datagenerator.core.schema.ConfigLoadException.class);
    }

    @Test
    void list_returnsCreatedDefinitions() throws Exception {
        Files.createDirectories(tempDir.resolve("jobs"));
        Files.writeString(tempDir.resolve("jobs/alpha.yaml"), "job: alpha\ntables: []");

        assertThat(service.list()).extracting("name").contains("alpha");
    }

    private static com.datagenerator.web.dto.JobDefinitionRequest request(String name, String content) {
        var request = new com.datagenerator.web.dto.JobDefinitionRequest();
        request.setName(name);
        request.setContent(content);
        return request;
    }
}
