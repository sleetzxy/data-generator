package com.datagenerator.ai.application;

import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.workflow.AgentExecutionWorkflow;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.web.dto.request.CreateSessionRequest;
import com.datagenerator.ai.application.sse.SseEvent;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Consumer;

/** Agent 会话应用服务：会话生命周期与对话编排入口。 */
public class AgentSessionApplicationService {

    private final AiProperties aiProperties;
    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final ChatMemoryStore chatMemoryStore;
    private final AgentSessionRegistry sessionRegistry;
    private final AgentExecutionWorkflow executionWorkflow;

    public AgentSessionApplicationService(
            AiProperties aiProperties,
            AgentRuntimeRegistry agentRuntimeRegistry,
            ChatMemoryStore chatMemoryStore,
            AgentSessionRegistry sessionRegistry,
            AgentExecutionWorkflow executionWorkflow) {
        this.aiProperties = aiProperties;
        this.agentRuntimeRegistry = agentRuntimeRegistry;
        this.chatMemoryStore = chatMemoryStore;
        this.sessionRegistry = sessionRegistry;
        this.executionWorkflow = executionWorkflow;
    }

    public List<AgentDescriptor> listAgents() {
        return agentRuntimeRegistry.listAgentIds().stream()
                .map(AgentDescriptor::new)
                .toList();
    }

    public List<ProviderDescriptor> listProviders() {
        List<ProviderDescriptor> providers = new ArrayList<>();
        for (Map.Entry<String, AiProperties.ProviderProperties> entry
                : aiProperties.getProviders().entrySet()) {
            AiProperties.ProviderProperties props = entry.getValue();
            if (!isProviderConfigured(props)) {
                continue;
            }
            String id = entry.getKey();
            providers.add(new ProviderDescriptor(
                    id,
                    formatProviderLabel(id, props),
                    props.getModel(),
                    false));
        }
        return providers;
    }

    /** SSE 长连接不设超时，避免 Tool 执行 + 模型推理超过固定时限后被断开。 */
    public Duration getStreamTimeout() {
        return Duration.ZERO;
    }

    public SessionSnapshot createSession(CreateSessionRequest request) {
        ensureProvidersAvailable();

        String agentId = resolveAgentId(request != null ? request.getAgentId() : null);
        if (!agentRuntimeRegistry.hasRuntime(agentId)) {
            throw new IllegalArgumentException("Unknown agent: " + agentId);
        }
        String provider = resolveProvider(agentId, request != null ? request.getProvider() : null);
        aiProperties.createStreamingModel(provider);

        evictExpiredSessions();
        if (sessionRegistry.size() >= aiProperties.getSession().getMaxSessions()) {
            throw new IllegalStateException("Maximum session limit reached");
        }

        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AgentSession session = new AgentSession(sessionId, agentId, provider, now);
        sessionRegistry.put(session);

        return toSnapshot(session);
    }

    public SessionSnapshot getSession(String sessionId) {
        AgentSession session = requireSession(sessionId);
        return toSnapshot(session);
    }

    public void deleteSession(String sessionId) {
        AgentSession session = sessionRegistry.remove(sessionId);
        if (session != null) {
            session.requestTurnCancellation();
            evictSession(session);
            session.endTurn();
        }
        chatMemoryStore.remove(sessionId);
    }

    public void sendMessage(String sessionId, String content, Consumer<SseEvent> emitter) {
        AgentSession session = requireSession(sessionId);
        if (!session.tryBeginTurn()) {
            throw new IllegalStateException("SESSION_CONFLICT: 会话 " + sessionId + " 已有进行中的对话");
        }
        touchSession(session);
        try {
            session.beginUserTurn(content);
            if (!agentRuntimeRegistry.hasRuntime(session.getAgentId())) {
                throw new IllegalStateException("No runtime registered for agent: " + session.getAgentId());
            }
            executionWorkflow.execute(session, session.getAgentId(), content, emitter);
        } catch (RuntimeException exception) {
            session.endTurn();
            throw exception;
        }
    }

    /** 客户端断开 SSE 或显式取消时中止进行中的模型流，但不删除会话。 */
    public void abortActiveTurn(String sessionId) {
        sessionRegistry.find(sessionId).ifPresent(this::abortActiveTurn);
    }

    private void abortActiveTurn(AgentSession session) {
        if (!session.isTurnInProgress()) {
            return;
        }
        session.requestTurnCancellation();
        evictSession(session);
        session.endTurn();
    }

    private AgentSession requireSession(String sessionId) {
        evictExpiredSessions();
        return sessionRegistry.find(sessionId)
                .orElseThrow(() -> new NoSuchElementException("SESSION_NOT_FOUND: " + sessionId));
    }

    private void touchSession(AgentSession session) {
        Instant now = Instant.now();
        session.setLastActiveAt(now);
        chatMemoryStore.touch(session.getSessionId());
    }

    private void evictExpiredSessions() {
        Instant cutoff = Instant.now().minus(aiProperties.getSession().getTtl());
        sessionRegistry.evictIfExpired(cutoff, session -> {
            if (session.isTurnInProgress()) {
                session.requestTurnCancellation();
            }
            evictSession(session);
            session.endTurn();
            chatMemoryStore.remove(session.getSessionId());
        });
    }

    private String resolveAgentId(String requestedAgentId) {
        if (!StringUtils.hasText(requestedAgentId)) {
            throw new IllegalArgumentException("agentId is required");
        }
        return requestedAgentId.trim();
    }

    private void ensureProvidersAvailable() {
        if (aiProperties.availableProviders().isEmpty()) {
            throw new IllegalStateException("AI_DISABLED: 未配置可用的 AI Provider，请检查 ai.providers");
        }
    }

    private String resolveProvider(String agentId, String requestedProvider) {
        if (StringUtils.hasText(requestedProvider)) {
            return requestedProvider.trim();
        }
        AiProperties.AgentProperties agentConfig = aiProperties.getAgents().get(agentId);
        if (agentConfig != null && StringUtils.hasText(agentConfig.getProvider())) {
            return agentConfig.getProvider().trim();
        }
        throw new IllegalStateException("AI_DISABLED: Agent 未配置 provider: " + agentId);
    }

    private SessionSnapshot toSnapshot(AgentSession session) {
        return new SessionSnapshot(
                session.getSessionId(),
                session.getAgentId(),
                session.getProvider(),
                session.getCreatedAt());
    }

    private static boolean isProviderConfigured(AiProperties.ProviderProperties props) {
        if (props == null) {
            return false;
        }
        return StringUtils.hasText(props.getApiKey()) || "ollama".equals(props.getType());
    }

    private static String formatProviderLabel(String id, AiProperties.ProviderProperties props) {
        String base = switch (id) {
            case "deepseek" -> "DeepSeek";
            case "openai" -> "OpenAI";
            case "ollama" -> "Ollama";
            default -> id;
        };
        if (StringUtils.hasText(props.getModel())) {
            return base + " · " + props.getModel();
        }
        return base;
    }

    private void evictSession(AgentSession session) {
        agentRuntimeRegistry.require(session.getAgentId()).evictSession(session.getSessionId());
    }

    public record AgentDescriptor(String id) {
    }

    public record ProviderDescriptor(String id, String label, String model, boolean defaultProvider) {
    }

    public record SessionSnapshot(
            String sessionId,
            String agentId,
            String provider,
            Instant createdAt) {
    }
}
