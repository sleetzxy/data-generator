package com.datagenerator.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.application.sse.SseEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseEventTest {

    @Test
    void token_serializesDeltaAsJson() {
        SseEvent event = SseEvent.token("hello \"world\"");
        assertThat(event.getEvent()).isEqualTo("token");
        assertThat(event.getData()).contains("\"delta\":\"hello \\\"world\\\"\"");
    }

    @Test
    void event_generic_serializesTypeAndPayload() {
        SseEvent event = SseEvent.event("job_saved", Map.of("status", "ok"));
        assertThat(event.getEvent()).isEqualTo("job_saved");
        assertThat(event.getData()).contains("\"status\":\"ok\"");
    }

    @Test
    void done_withFields_includesAllFields() {
        SseEvent event = SseEvent.done(Map.of(
                "messageId", "msg-1",
                "draftIncomplete", true,
                "draftValidated", false,
                "hasDraft", true));
        assertThat(event.getEvent()).isEqualTo("done");
        assertThat(event.getData()).contains("\"draftIncomplete\":true");
        assertThat(event.getData()).contains("\"draftValidated\":false");
        assertThat(event.getData()).contains("\"hasDraft\":true");
        assertThat(event.getData()).contains("\"messageId\":\"msg-1\"");
    }

    @Test
    void done_noArgs_returnsMinimalDone() {
        SseEvent event = SseEvent.done();
        assertThat(event.getEvent()).isEqualTo("done");
    }

    @Test
    void tool_withMeta_includesMetadata() {
        SseEvent event = SseEvent.tool("validate", "done",
                Map.of("duration_ms", 152));
        assertThat(event.getEvent()).isEqualTo("tool");
        assertThat(event.getData()).contains("\"name\":\"validate\"");
        assertThat(event.getData()).contains("\"duration_ms\":152");
    }

    @Test
    void error_serializesCodeAndMessage() {
        SseEvent event = SseEvent.error("TIMEOUT", "请求超时");
        assertThat(event.getEvent()).isEqualTo("error");
        assertThat(event.getData()).contains("\"code\":\"TIMEOUT\"");
        assertThat(event.getData()).contains("\"message\":\"请求超时\"");
    }
}
