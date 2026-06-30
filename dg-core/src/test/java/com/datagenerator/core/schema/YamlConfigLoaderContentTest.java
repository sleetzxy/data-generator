package com.datagenerator.core.schema;

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
    void loadJobFromContent_validYaml_parsesTables() {
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
        JobDefinition job = loader.loadJobFromContent(yaml);
        assertThat(job.getTables()).hasSize(1);
        assertThat(job.getTables().get(0).getName()).isEqualTo("t1");
    }

    @Test
    void loadJobFromContent_missingTables_throws() {
        assertThatThrownBy(() -> loader.loadJobFromContent("writer: { type: csv }"))
                .isInstanceOf(ConfigLoadException.class);
    }
}
