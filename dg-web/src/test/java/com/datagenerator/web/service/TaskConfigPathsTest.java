package com.datagenerator.web.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfigPathsTest {

    @Test
    void toConfigPath_appendsDirAndExtension() {
        assertThat(TaskConfigPaths.toConfigPath("demo")).isEqualTo("task-configs/demo.yaml");
        assertThat(TaskConfigPaths.toConfigPath("demo.yaml")).isEqualTo("task-configs/demo.yaml");
        assertThat(TaskConfigPaths.toConfigPath("task-configs/demo")).isEqualTo("task-configs/demo.yaml");
    }

    @Test
    void toFileName_extractsBasename() {
        assertThat(TaskConfigPaths.toFileName("task-configs/demo.yaml")).isEqualTo("demo");
        assertThat(TaskConfigPaths.toFileName("task-configs/demo")).isEqualTo("demo");
        assertThat(TaskConfigPaths.toFileName("demo.yaml")).isEqualTo("demo");
    }
}
