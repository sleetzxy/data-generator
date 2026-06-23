package com.datagenerator.web.service;

import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.web.dto.JobYamlValidationResponse;
import com.datagenerator.web.storage.JobScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JobDefinitionServiceValidateYamlTest {

    @Mock
    private JobScheduleService scheduleService;

    @Mock
    private JobScheduleManager scheduleManager;

    @Mock
    private JobScheduleExecutor scheduleExecutor;

    @Mock
    private JobScheduleRepository scheduleRepository;

    private JobDefinitionService jobDefinitionService;

    @BeforeEach
    void setUp() {
        jobDefinitionService = new JobDefinitionService(
                ConfigPathResolver.forClasspath(getClass().getClassLoader()),
                scheduleService,
                scheduleManager,
                scheduleExecutor,
                scheduleRepository);
    }

    @Test
    void validateYaml_valid_returnsOk() {
        String yaml = """
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

        JobYamlValidationResponse result = jobDefinitionService.validateYaml(yaml);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateYaml_invalid_returnsErrors() {
        JobYamlValidationResponse result = jobDefinitionService.validateYaml("tables: []");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }
}
