package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.web.dto.TaskConfigValidationResponse;
import com.datagenerator.web.storage.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TaskConfigServiceValidateYamlTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskScheduleService scheduleService;

    @Mock
    private TaskScheduleManager scheduleManager;

    @Mock
    private TaskRunQueueExecutor scheduleExecutor;

    private TaskConfigService taskConfigService;

    @BeforeEach
    void setUp() {
        taskConfigService = new TaskConfigService(
                ConfigPathResolver.forClasspath(getClass().getClassLoader()),
                taskRepository,
                scheduleService,
                scheduleManager,
                scheduleExecutor);
    }

    @Test
    void validateYaml_withIdAndName_returnsOk() {
        String yaml = """
                id: validate_test
                name: 校验测试
                writer:
                  type: csv
                  connection: local-csv
                tables:
                  - name: t1
                    count: 10
                    schema:
                      table: t1
                      fields:
                        - name: id
                          type: BIGINT
                          generator: { strategy: sequence, start: 1 }
                """;

        TaskConfigValidationResponse result = taskConfigService.validateYaml(yaml);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateYaml_withoutIdAndName_returnsOk() {
        String yaml = """
                writer:
                  type: csv
                  connection: local-csv
                tables:
                  - name: t1
                    count: 10
                """;

        TaskConfigValidationResponse result = taskConfigService.validateYaml(yaml);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateYaml_invalid_returnsErrors() {
        TaskConfigValidationResponse result = taskConfigService.validateYaml("tables: []");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void validateYaml_nonMappingContent_returnsErrors() {
        // 非 mapping YAML（如纯字符串）此前会泄漏 IllegalArgumentException 导致 400
        TaskConfigValidationResponse result =
                taskConfigService.validateYaml("just a plain string");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }
}
