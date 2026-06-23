package com.datagenerator.ai.web;

import com.datagenerator.ai.service.AgentSessionService;
import com.datagenerator.ai.service.SseEventFactory;
import com.datagenerator.ai.dto.SseEvent;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class AgentSseSupport {

    private AgentSseSupport() {
    }

    public static SseEmitter openStream(AgentSessionService agentSessionService) {
        SseEmitter emitter = new SseEmitter(agentSessionService.getStreamTimeout().toMillis());
        emitter.onTimeout(emitter::complete);
        emitter.onError(emitter::completeWithError);
        return emitter;
    }

    public static void sendMessageAsync(
            AgentSessionService agentSessionService,
            SseEmitter emitter,
            String sessionId,
            String content) {
        CompletableFuture.runAsync(() -> {
            try {
                agentSessionService.sendMessage(
                        sessionId, content, event -> forwardEvent(emitter, event));
            } catch (Exception exception) {
                emitErrorAndClose(emitter, exception);
            }
        });
    }

    public static void forwardEvent(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.getEvent()).data(event.getData()));
            if ("done".equals(event.getEvent()) || "error".equals(event.getEvent())) {
                emitter.complete();
            }
        } catch (IOException exception) {
            emitErrorAndClose(emitter, exception);
        }
    }

    private static void emitErrorAndClose(SseEmitter emitter, Exception exception) {
        try {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            forwardEvent(emitter, SseEventFactory.error("AGENT_ERROR", message));
        } catch (Exception ignored) {
            emitter.completeWithError(exception);
        }
    }
}
