package com.datagenerator.ai.service;

import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.config.ChatModelFactory;
import com.datagenerator.ai.dto.CreateSessionRequest;
import com.datagenerator.ai.dto.ProviderInfo;
import com.datagenerator.ai.dto.SessionResponse;
import com.datagenerator.ai.dto.SkillInfo;
import com.datagenerator.ai.dto.SseEvent;
import com.datagenerator.ai.service.exception.AiDisabledException;
import com.datagenerator.ai.service.exception.SessionNotFoundException;
import com.datagenerator.ai.session.AgentSession;
import com.datagenerator.ai.session.AgentSessionRegistry;
import com.datagenerator.ai.session.ChatMemoryStore;
import com.datagenerator.ai.skill.SkillCatalog;
import com.datagenerator.ai.skill.runtime.SkillExecutionContext;
import com.datagenerator.ai.skill.runtime.SkillRuntime;
import com.datagenerator.ai.skill.runtime.SkillRuntimeRegistry;
import com.datagenerator.ai.util.TextUtils;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);
    private static final int MAX_STREAM_ATTEMPTS = 2;

    private final AiProperties aiProperties;
    private final SkillCatalog skillCatalog;
    private final SkillRuntimeRegistry skillRuntimeRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ChatMemoryStore chatMemoryStore;
    private final AgentSessionRegistry sessionRegistry;

    public AgentSessionService(
            AiProperties aiProperties,
            SkillCatalog skillCatalog,
            SkillRuntimeRegistry skillRuntimeRegistry,
            ChatModelFactory chatModelFactory,
            ChatMemoryStore chatMemoryStore,
            AgentSessionRegistry sessionRegistry) {
        this.aiProperties = aiProperties;
        this.skillCatalog = skillCatalog;
        this.skillRuntimeRegistry = skillRuntimeRegistry;
        this.chatModelFactory = chatModelFactory;
        this.chatMemoryStore = chatMemoryStore;
        this.sessionRegistry = sessionRegistry;
    }

    public List<SkillInfo> listSkills() {
        return skillCatalog.list().stream()
                .filter(skill -> skillRuntimeRegistry.hasRuntime(skill.getId()))
                .toList();
    }

    public List<ProviderInfo> listProviders() {
        return chatModelFactory.listProviderInfos();
    }

    /** SSE 长连接不设超时，避免 Tool 执行 + 模型推理超过固定时限后被断开。 */
    public Duration getStreamTimeout() {
        return Duration.ZERO;
    }

    public SessionResponse createSession(CreateSessionRequest request) {
        ensureProvidersAvailable();

        String skillId = request.getSkillId();
        if (!isRunnableSkill(skillId)) {
            throw new IllegalArgumentException("Unknown skill: " + skillId);
        }

        String provider = resolveProvider(request.getProvider());
        chatModelFactory.getStreamingModel(provider);

        evictExpiredSessions();
        if (sessionRegistry.size() >= aiProperties.getSession().getMaxSessions()) {
            throw new IllegalStateException("Maximum session limit reached");
        }

        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AgentSession session = new AgentSession(sessionId, skillId, provider, now);
        sessionRegistry.put(session);

        return toResponse(session);
    }

    public SessionResponse getSession(String sessionId) {
        AgentSession session = requireSession(sessionId);
        return toResponse(session);
    }

    public void deleteSession(String sessionId) {
        AgentSession session = sessionRegistry.remove(sessionId);
        if (session != null) {
            skillRuntimeRegistry.require(session.getSkillId()).evictSession(sessionId);
        }
        chatMemoryStore.remove(sessionId);
    }

    public void sendMessage(String sessionId, String content, Consumer<SseEvent> emitter) {
        AgentSession session = requireSession(sessionId);
        touchSession(session);

        SkillRuntime runtime = skillRuntimeRegistry.require(session.getSkillId());
        SkillExecutionContext context = new SkillExecutionContext(
                session.getProvider(), chatModelFactory, chatMemoryStore, skillCatalog);
        startStream(session, runtime, context, sessionId, content, emitter, 1);
    }

    private void startStream(
            AgentSession session,
            SkillRuntime runtime,
            SkillExecutionContext context,
            String sessionId,
            String content,
            Consumer<SseEvent> emitter,
            int attempt) {
        AtomicBoolean outputStarted = new AtomicBoolean(false);
        TokenStream tokenStream = runtime.chat(sessionId, content, context);
        tokenStream
                .onPartialResponse(delta -> {
                    outputStarted.set(true);
                    emitter.accept(SseEventFactory.token(delta));
                })
                .onToolExecuted(tool -> {
                    outputStarted.set(true);
                    emitter.accept(SseEventFactory.tool(
                            tool.request() != null ? tool.request().name() : "unknown",
                            "done"));
                })
                .onCompleteResponse(response -> handleComplete(session, runtime, response, emitter))
                .onError(error -> handleStreamError(
                        error,
                        outputStarted.get(),
                        attempt,
                        session,
                        runtime,
                        context,
                        sessionId,
                        content,
                        emitter))
                .start();
    }

    private void handleStreamError(
            Throwable error,
            boolean outputStarted,
            int attempt,
            AgentSession session,
            SkillRuntime runtime,
            SkillExecutionContext context,
            String sessionId,
            String content,
            Consumer<SseEvent> emitter) {
        Throwable root = unwrap(error);
        if (!outputStarted && attempt < MAX_STREAM_ATTEMPTS && isRetryableStreamError(root)) {
            log.warn(
                    "Agent streaming failed (attempt {}/{}), retrying: {}",
                    attempt,
                    MAX_STREAM_ATTEMPTS,
                    root.getMessage());
            startStream(session, runtime, context, sessionId, content, emitter, attempt + 1);
            return;
        }
        handleError(root, emitter);
    }

    private void handleComplete(
            AgentSession session,
            SkillRuntime runtime,
            ChatResponse response,
            Consumer<SseEvent> emitter) {
        String fullText = response.aiMessage() != null ? response.aiMessage().text() : "";
        runtime.onComplete(session, fullText, emitter);

        String messageId = UUID.randomUUID().toString();
        emitter.accept(SseEventFactory.done(messageId));
    }

    private void handleError(Throwable error, Consumer<SseEvent> emitter) {
        Throwable root = unwrap(error);
        // 特殊处理模型输出的工具参数 JSON 解析错误，给前端更友好的提示并降低日志级别
        if (isTruncatedToolArgumentsError(root)) {
            log.warn("Agent streaming produced invalid tool arguments JSON: {}", root.getMessage());
            emitter.accept(SseEventFactory.error(
                    "INVALID_TOOL_ARGS",
                    "模型生成的工具参数不是合法 JSON，可能被截断或格式不正确。请尝试缩短输入或调整提示以输出严格 JSON。"));
            return;
        }

        // 其他错误降级为 warn（避免大量错误堆栈噪声），同时返回统一错误事件
        log.warn("Agent streaming failed: {}", root.getMessage());
        emitter.accept(SseEventFactory.error("AGENT_ERROR", formatStreamError(root)));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isRetryableStreamError(Throwable error) {
        if (error instanceof java.net.SocketException) {
            return true;
        }
        if (isTruncatedToolArgumentsError(error)) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("broken pipe");
    }

    private static boolean isTruncatedToolArgumentsError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof com.fasterxml.jackson.core.JsonParseException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("jsoneofexception")
                        || lower.contains("end-of-input")
                        || lower.contains("expecting closing quote")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String formatStreamError(Throwable error) {
        if (isTruncatedToolArgumentsError(error)) {
            return "Tool 参数 JSON 被截断（YAML 过长或模型输出达到上限）。"
                    + "请先生成 YAML 到回复正文（<!-- dg-artifact:yaml -->），"
                    + "再使用 validateDraftJobYaml / previewDraftJobYaml 校验或预览，或拆步后重试";
        }
        if (error instanceof java.net.SocketException) {
            return "网络连接被重置，可能是请求超时或上游 AI 服务断开，请重试";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private AgentSession requireSession(String sessionId) {
        evictExpiredSessions();
        return sessionRegistry.find(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private void touchSession(AgentSession session) {
        Instant now = Instant.now();
        session.setLastActiveAt(now);
        chatMemoryStore.touch(session.getSessionId());
    }

    private void evictExpiredSessions() {
        Instant cutoff = Instant.now().minus(aiProperties.getSession().getTtl());
        sessionRegistry.evictIfExpired(cutoff, session -> {
            skillRuntimeRegistry.require(session.getSkillId()).evictSession(session.getSessionId());
            chatMemoryStore.remove(session.getSessionId());
        });
    }

    private boolean isRunnableSkill(String skillId) {
        return skillCatalog.get(skillId) != null && skillRuntimeRegistry.hasRuntime(skillId);
    }

    private void ensureProvidersAvailable() {
        if (chatModelFactory.availableProviders().isEmpty()) {
            throw new AiDisabledException("未配置可用的 AI Provider，请检查 ai.providers");
        }
    }

    private String resolveProvider(String requestedProvider) {
        String provider = TextUtils.hasText(requestedProvider)
                ? requestedProvider
                : aiProperties.getDefaultProvider();
        if (!TextUtils.hasText(provider)) {
            throw new AiDisabledException("未配置默认 AI Provider");
        }
        return provider;
    }

    private SessionResponse toResponse(AgentSession session) {
        return new SessionResponse(
                session.getSessionId(),
                session.getSkillId(),
                session.getProvider(),
                session.getCreatedAt(),
                session.getDraftYaml());
    }
}
