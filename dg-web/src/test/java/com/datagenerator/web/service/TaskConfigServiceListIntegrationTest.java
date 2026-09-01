package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.web.dto.TaskConfigListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TaskConfigServiceListIntegrationTest {

    @Mock
    private TaskScheduleService scheduleService;
    @Mock
    private TaskScheduleManager scheduleManager;
    @Mock
    private TaskRunQueueExecutor scheduleExecutor;
    @Mock
    private com.datagenerator.web.storage.TaskScheduleRepository scheduleRepository;

    @TempDir
    Path overlayDir;

    private TaskConfigService taskConfigService;

    @BeforeEach
    void setUp() throws Exception {
        // overlay 目录放置一个自定义配置，验证列表合并内置 fixture 与 overlay 配置
        Path overlayConfig = overlayDir.resolve("task-configs/custom_sample.yaml");
        Files.createDirectories(overlayConfig.getParent());
        Files.writeString(overlayConfig, """
                id: custom_sample
                name: 自定义示例
                writer:
                  type: csv
                  connection: local-csv
                  mode: insert
                tables:
                  - name: customers
                    count: 10
                    schema:
                      table: customers
                      fields:
                        - name: id
                          type: BIGINT
                          generator: { strategy: sequence, start: 1, step: 1 }
                """, StandardCharsets.UTF_8);
        ConfigPathResolver resolver = ConfigPathResolver.fromSetting(
                "classpath:fixtures",
                getClass().getClassLoader(),
                overlayDir);
        taskConfigService = new TaskConfigService(
                resolver, scheduleService, scheduleManager, scheduleExecutor, scheduleRepository);
    }

    @Test
    void list_allConfigsIncludingOverlay_loadsSuccessfully() {
        TaskConfigListResponse result = taskConfigService.list();

        assertThat(result.items()).extracting("fileName")
                .contains("sample", "custom_sample");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void list_withNameKeyword_filtersByDisplayName() {
        TaskConfigListResponse result = taskConfigService.list("自定义");

        assertThat(result.items()).extracting("fileName")
                .containsExactly("custom_sample");
    }

    @Test
    void list_withBrokenOverlayConfig_reportsSkipped() throws Exception {
        Path broken = overlayDir.resolve("task-configs/broken.yaml");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "id: broken\ntables: []", StandardCharsets.UTF_8);

        TaskConfigListResponse result = taskConfigService.list();

        assertThat(result.skipped()).extracting("path")
                .contains("task-configs/broken.yaml");
    }

    @Test
    void list_withPageAndSize_returnsSliceAndTotal() {
        TaskConfigListResponse all = taskConfigService.list();
        int total = all.items().size();

        TaskConfigListResponse firstPage = taskConfigService.list(null, 1, 1);

        assertThat(firstPage.total()).isEqualTo(total);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().get(0).getFileName()).isEqualTo(all.items().get(0).getFileName());

        TaskConfigListResponse secondPage = taskConfigService.list(null, 2, 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).getFileName()).isEqualTo(all.items().get(1).getFileName());
    }

    @Test
    void list_withPageBeyondRange_returnsEmptyItemsWithTotal() {
        TaskConfigListResponse result = taskConfigService.list(null, 99, 10);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isGreaterThanOrEqualTo(0);
    }
}
