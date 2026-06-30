package com.datagenerator.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

class AgentIoLoggerTest {

    @Test
    void clip_whenDisabled_doesNotThrow() {
        AgentIoLogger logger = AgentIoLogger.disabled();
        logger.logModelInput("s1", "user", 1, "hello");
        assertThat(logger.isEnabled()).isFalse();
    }

    @Test
    void clip_longText_omitsMiddle() {
        AiProperties properties = new AiProperties();
        properties.getIoLogging().setEnabled(true);
        properties.getIoLogging().setMaxChars(200);
        AgentIoLogger logger = new AgentIoLogger(properties);

        String longText = "a".repeat(500);
        logger.logModelOutput("s1", null, longText);
        assertThat(logger.isEnabled()).isTrue();
    }
}
