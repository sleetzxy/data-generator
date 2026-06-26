package com.datagenerator.ai.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.SseEventFactory;
import com.datagenerator.ai.web.dto.common.SseEvent;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentSseSupportTest {

    @Test
    void forwardEvent_done_ignoresFurtherEvents() {
        SseEmitter emitter = new SseEmitter(5_000L);

        assertThatCode(() -> {
            AgentSseSupport.forwardEvent(emitter, SseEventFactory.token("x"));
            AgentSseSupport.forwardEvent(emitter, SseEventFactory.done("msg-1"));
            AgentSseSupport.forwardEvent(emitter, SseEventFactory.token("ignored"));
        }).doesNotThrowAnyException();
    }

    @Test
    void sendMessageAsync_done_doesNotDeleteSession() {
        AgentSessionApplicationService sessionService = mock(AgentSessionApplicationService.class);
        SseEmitter emitter = new SseEmitter(5_000L);

        doAnswer(invocation -> {
            java.util.function.Consumer<SseEvent> consumer = invocation.getArgument(2);
            consumer.accept(SseEventFactory.token("ok"));
            consumer.accept(SseEventFactory.done("msg-1", false, false, false));
            return null;
        }).when(sessionService).sendMessage(any(), any(), any());

        Executor directExecutor = Runnable::run;
        AgentSseSupport.sendMessageAsync(sessionService, emitter, "session-1", "hello", directExecutor);

        verify(sessionService, never()).deleteSession("session-1");
    }
}
