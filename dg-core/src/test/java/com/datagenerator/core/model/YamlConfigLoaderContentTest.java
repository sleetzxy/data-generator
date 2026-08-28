package com.datagenerator.core.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigLoaderContentTest {

    private YamlConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new YamlConfigLoader(ConfigPathResolver.forClasspath(getClass().getClassLoader()));
    }

    @Test
    void loadTaskConfigFromContent_validYaml_parsesTables() {
        String yaml = """
                id: content_test
                name: 内容解析测试
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
        TaskConfig taskConfig = loader.loadTaskConfigFromContent(yaml);
        assertThat(taskConfig.getTables()).hasSize(1);
        assertThat(taskConfig.getTables().get(0).getName()).isEqualTo("t1");
    }

    @Test
    void loadTaskConfigFromContent_missingTables_throws() {
        assertThatThrownBy(() -> loader.loadTaskConfigFromContent("writer: { type: csv }"))
                .isInstanceOf(ConfigLoadException.class);
    }
}
