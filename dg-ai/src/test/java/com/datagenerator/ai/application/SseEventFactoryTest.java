package com.datagenerator.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.web.dto.common.SseEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseEventFactoryTest {

    @Test
    void token_serializesDeltaAsJson() {
        SseEvent event = SseEventFactory.token("hello \"world\"");

        assertThat(event.getEvent()).isEqualTo("token");
        assertThat(event.getData()).contains("\"delta\":\"hello \\\"world\\\"\"");
    }

    @Test
    void jobSaved_serializesStatus() {
        SseEvent event = SseEventFactory.jobSaved();

        assertThat(event.getEvent()).isEqualTo("job_saved");
        assertThat(event.getData()).contains("\"status\":\"ok\"");
    }

    @Test
    void done_includesDraftStatus() {
        SseEvent event = SseEventFactory.done("msg-1", true, false, true);

        assertThat(event.getEvent()).isEqualTo("done");
        assertThat(event.getData()).contains("\"draftIncomplete\":true");
        assertThat(event.getData()).contains("\"draftValidated\":false");
        assertThat(event.getData()).contains("\"hasDraft\":true");
    }

    @Test
    void validationError_serializesErrors() {
        SseEvent event = SseEventFactory.validationError(List.of("缺少 tables"));

        assertThat(event.getEvent()).isEqualTo("validation_error");
        assertThat(event.getData()).contains("缺少 tables");
    }
}
