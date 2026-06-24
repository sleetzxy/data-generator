package com.datagenerator.ai.web;

import com.datagenerator.ai.service.AgentSessionService;
import com.datagenerator.ai.service.SseEventFactory;
import com.datagenerator.ai.dto.SseEvent;
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

    public static SseEmitter openStream(AgentSessionService agentSessionService) {
        SseEmitter emitter = new SseEmitter(agentSessionService.getStreamTimeout().toMillis());

        // 注册回调以确保 COMPLETED 状态与 SseEmitter 生命周期保持一致
        emitter.onCompletion(() -> {
            try {
                AtomicBoolean flag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(true));
                flag.set(true);
            } finally {
                COMPLETED.remove(emitter);
            }
        });

        emitter.onTimeout(() -> {
            try {
                AtomicBoolean flag = COMPLETED.computeIfAbsent(emitter, e -> new AtomicBoolean(true));
                flag.set(true);
                // 尝试完成流，如果已完成则忽略异常
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            } finally {
                COMPLETED.remove(emitter);
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
            }
        });

        return emitter;
    }

    /**
     * 兼容旧调用，使公共线程池执行异步任务。
     */
    public static void sendMessageAsync(
            AgentSessionService agentSessionService,
            SseEmitter emitter,
            String sessionId,
            String content) {

        // 在 emitter 生命周期结束时，尝试取消后端会话以释放资源
        emitter.onCompletion(() -> {
            try {
                agentSessionService.deleteSession(sessionId);
            } catch (Exception ignored) {
            }
        });
        emitter.onTimeout(() -> {
            try {
                agentSessionService.deleteSession(sessionId);
            } catch (Exception ignored) {
            }
        });
        emitter.onError(throwable -> {
            try {
                agentSessionService.deleteSession(sessionId);
            } catch (Exception ignored) {
            }
        });

        CompletableFuture.runAsync(() -> {
            try {
                agentSessionService.sendMessage(
                        sessionId, content, event -> forwardEvent(emitter, event));
            } catch (Exception exception) {
                emitErrorAndClose(emitter, exception);
            }
        });
    }

    /**
     * 使用注入的 Executor 执行异步任务，避免使用公共线程池。
     */
    public static void sendMessageAsync(
            AgentSessionService agentSessionService,
            SseEmitter emitter,
            String sessionId,
            String content,
            Executor executor) {

        // 在 emitter 生命周期结束时，尝试取消后端会话以释放资源
        emitter.onCompletion(() -> {
            try {
                agentSessionService.deleteSession(sessionId);
            } catch (Exception ignored) {
            }
        });
        emitter.onTimeout(() -> {
            try {
                agentSessionService.deleteSession(sessionId);
            } catch (Exception ignored) {
            }
        });
        emitter.onError(throwable -> {
            try {
                agentSessionService.deleteSession(sessionId);
            } catch (Exception ignored) {
            }
        });

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
            return; // already completed or errored
        }
        try {
            try {
                emitter.send(SseEmitter.event().name(event.getEvent()).data(event.getData()));
            } catch (IllegalStateException ise) {
                // SseEmitter 已被外部完成（比如客户端断开），将其标记为已完成并清理状态，避免继续发送
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
                // ignore
            }
            return;
        }
        try {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            // send single error event then complete
            if (doneFlag.compareAndSet(false, true)) {
                try {
                    forwardEvent(emitter, SseEventFactory.error("AGENT_ERROR", message));
                } catch (Exception ignored) {
                    try {
                        emitter.completeWithError(exception);
                    } catch (Exception ignored2) {
                        // ignore
                    }
                } finally {
                    COMPLETED.remove(emitter);
                }
            }
        } catch (Exception ignored) {
            try {
                emitter.completeWithError(exception);
            } catch (Exception ignored2) {
                // ignore
            }
        }
    }
}
