package com.datagenerator.ai.tool.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolResultTextsTest {

    @Test
    void ensureNonBlank_blankInput_returnsFallback() {
        assertThat(ToolResultTexts.ensureNonBlank(null, "getJobYaml"))
                .contains("getJobYaml")
                .isNotBlank();
    }
}
