package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;

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

    private TaskConfigService taskConfigService;

    @BeforeEach
    void setUp() {
        ConfigPathResolver resolver = ConfigPathResolver.fromSetting(
                "classpath:configs",
                getClass().getClassLoader(),
                Path.of("E:/探索/data-generator/data/configs"));
        taskConfigService = new TaskConfigService(
                resolver, scheduleService, scheduleManager, scheduleExecutor, scheduleRepository);
    }

    @Test
    void list_allConfigsIncludingOverlay_loadsSuccessfully() {
        assertThatCode(() -> taskConfigService.list())
                .doesNotThrowAnyException();
    }

    @Test
    void list_withNameKeyword_loadsSuccessfully() {
        assertThatCode(() -> taskConfigService.list("长春"))
                .doesNotThrowAnyException();
    }
}
