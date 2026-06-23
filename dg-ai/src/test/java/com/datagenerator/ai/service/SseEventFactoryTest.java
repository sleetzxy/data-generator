package com.datagenerator.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.dto.SseEvent;
import org.junit.jupiter.api.Test;

class SseEventFactoryTest {

    @Test
    void token_serializesDeltaAsJson() {
        SseEvent event = SseEventFactory.token("hello \"world\"");

        assertThat(event.getEvent()).isEqualTo("token");
        assertThat(event.getData()).contains("\"delta\":\"hello \\\"world\\\"\"");
    }

    @Test
    void artifactYaml_includesTypeAndContent() {
        SseEvent event = SseEventFactory.artifactYaml("writer:\n  type: csv");

        assertThat(event.getEvent()).isEqualTo("artifact");
        assertThat(event.getData()).contains("\"type\":\"yaml\"");
        assertThat(event.getData()).contains("writer");
    }
}
