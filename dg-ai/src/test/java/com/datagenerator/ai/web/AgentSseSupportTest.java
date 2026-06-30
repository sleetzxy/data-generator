package com.datagenerator.ai.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.sse.SseEvent;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentSseSupportTest {

    @Test
    void forwardEvent_done_ignoresFurtherEvents() {
        SseEmitter emitter = new SseEmitter(5_000L);

        assertThatCode(() -> {
            AgentSseSupport.forwardEvent(emitter, SseEvent.token("x"));
            AgentSseSupport.forwardEvent(emitter, SseEvent.done(Map.of("messageId", "msg-1")));
            AgentSseSupport.forwardEvent(emitter, SseEvent.token("ignored"));
        }).doesNotThrowAnyException();
    }

    @Test
    void sendMessageAsync_done_doesNotDeleteSession() {
        AgentSessionApplicationService sessionService = mock(AgentSessionApplicationService.class);
        SseEmitter emitter = new SseEmitter(5_000L);

        doAnswer(invocation -> {
            java.util.function.Consumer<SseEvent> consumer = invocation.getArgument(2);
            consumer.accept(SseEvent.token("ok"));
            consumer.accept(SseEvent.done(Map.of("messageId", "msg-1")));
            return null;
        }).when(sessionService).sendMessage(any(), any(), any());

        Executor directExecutor = Runnable::run;
        AgentSseSupport.sendMessageAsync(sessionService, emitter, "session-1", "hello", directExecutor);

        verify(sessionService, never()).deleteSession("session-1");
    }
}
