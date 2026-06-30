package com.datagenerator.ai.web;

import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.sse.SseEvent;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class AgentSseSupport {

    private AgentSseSupport() {
    }

    private static final ConcurrentMap<SseEmitter, AtomicBoolean> COMPLETED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<SseEmitter, String> EMITTER_SESSIONS = new ConcurrentHashMap<>();

    public static SseEmitter openStream(
            AgentSessionApplicationService agentSessionService, String sessionId) {
        SseEmitter emitter = new SseEmitter(agentSessionService.getStreamTimeout().toMillis());
        EMITTER_SESSIONS.put(emitter, sessionId);

        Runnable onDisconnect = () -> {
            EMITTER_SESSIONS.remove(emitter);
            agentSessionService.abortActiveTurn(sessionId);
        };

        emitter.onCompletion(() -> {
            try {
                AtomicBoolean flag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(true));
                flag.set(true);
            } finally {
                COMPLETED.remove(emitter);
                onDisconnect.run();
            }
        });

        emitter.onTimeout(() -> {
            try {
                AtomicBoolean flag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(true));
                flag.set(true);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            } finally {
                COMPLETED.remove(emitter);
                onDisconnect.run();
            }
        });

        emitter.onError(throwable -> {
            try {
                AtomicBoolean flag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(true));
                flag.set(true);
                try {
                    emitter.completeWithError(throwable);
                } catch (Exception ignored) {
                }
            } finally {
                COMPLETED.remove(emitter);
                onDisconnect.run();
            }
        });

        return emitter;
    }

    /**
     * 兼容旧调用，使公共线程池执行异步任务。
     */
    public static void sendMessageAsync(
            AgentSessionApplicationService agentSessionService,
            SseEmitter emitter,
            String sessionId,
            String content) {
        sendMessageAsync(agentSessionService, emitter, sessionId, content, Runnable::run);
    }

    /**
     * 使用注入的 Executor 执行异步任务，避免使用公共线程池。
     * 会话生命周期由 TTL 与显式 DELETE 管理，SSE 关闭时不删除会话。
     */
    public static void sendMessageAsync(
            AgentSessionApplicationService agentSessionService,
            SseEmitter emitter,
            String sessionId,
            String content,
            Executor executor) {

        CompletableFuture.runAsync(() -> {
            try {
                agentSessionService.sendMessage(
                        sessionId, content, event -> forwardEvent(emitter, event));
            } catch (Exception exception) {
                emitErrorAndClose(emitter, exception);
            }
        }, executor);
    }

    public static void forwardEvent(SseEmitter emitter, SseEvent event) {
        AtomicBoolean doneFlag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(false));
        if (doneFlag.get()) {
            return;
        }
        try {
            try {
                emitter.send(SseEmitter.event().name(event.getEvent()).data(event.getData()));
            } catch (IllegalStateException ise) {
                doneFlag.set(true);
                COMPLETED.remove(emitter);
                return;
            }
            if ("done".equals(event.getEvent()) || "error".equals(event.getEvent())) {
                if (doneFlag.compareAndSet(false, true)) {
                    try {
                        emitter.complete();
                    } finally {
                        COMPLETED.remove(emitter);
                        EMITTER_SESSIONS.remove(emitter);
                    }
                }
            }
        } catch (IOException exception) {
            emitErrorAndClose(emitter, exception);
        }
    }

    private static void emitErrorAndClose(SseEmitter emitter, Exception exception) {
        AtomicBoolean doneFlag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(false));
        if (doneFlag.get()) {
            try {
                emitter.completeWithError(exception);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            if (doneFlag.compareAndSet(false, true)) {
                try {
                    forwardEvent(emitter, SseEvent.error("AGENT_ERROR", message));
                } catch (Exception ignored) {
                    try {
                        emitter.completeWithError(exception);
                    } catch (Exception ignored2) {
                    }
                } finally {
                    COMPLETED.remove(emitter);
                    EMITTER_SESSIONS.remove(emitter);
                }
            }
        } catch (Exception ignored) {
            try {
                emitter.completeWithError(exception);
            } catch (Exception ignored2) {
            }
        }
    }
}
