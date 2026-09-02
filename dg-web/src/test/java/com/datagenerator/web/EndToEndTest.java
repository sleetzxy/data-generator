package com.datagenerator.web;

import com.datagenerator.web.storage.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = DataGeneratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndTest {

    private static final String PREVIEW_SMOKE_FILE_NAME = "preview_smoke";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void registerPreviewSmokeTask() {
        // 预览与运行提交一致要求任务在主表登记：先清理历史残留再插入冒烟任务行（调度关闭）
        taskRepository.deleteByFileName(PREVIEW_SMOKE_FILE_NAME);
        taskRepository.insert(new TaskRepository.TaskRecord(
                PREVIEW_SMOKE_FILE_NAME,
                PREVIEW_SMOKE_FILE_NAME,
                "Preview Smoke",
                false,
                null,
                Instant.now().toString(),
                null));
    }

    @AfterEach
    void cleanupPreviewSmokeTask() {
        taskRepository.deleteByFileName(PREVIEW_SMOKE_FILE_NAME);
    }

    @Test
    void preview_singleCustomer_returns200() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                  "configPath": "task-configs/preview_smoke.yaml",
                  "preview": { "limit": 5 }
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/preview",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"COMPLETED\"");
        assertThat(response.getBody()).contains("\"tables\"");
    }
}
