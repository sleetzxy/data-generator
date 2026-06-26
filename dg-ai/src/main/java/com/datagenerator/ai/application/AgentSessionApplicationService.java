package com.datagenerator.ai.application;

import com.datagenerator.ai.agent.orchestrator.AgentOrchestrator;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.workflow.AgentConversationWorkflow;
import com.datagenerator.ai.application.workflow.AgentRoutingWorkflow;
import com.datagenerator.ai.application.dto.SessionSnapshot;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.web.dto.request.CreateSessionRequest;
import com.datagenerator.ai.web.dto.response.AgentInfo;
import com.datagenerator.ai.web.dto.response.ProviderInfo;
import com.datagenerator.ai.web.dto.common.SseEvent;
import com.datagenerator.ai.web.WebDtoMapper;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.model.adapter.ChatModelFactory;
import com.datagenerator.ai.exception.AiDisabledException;
import com.datagenerator.ai.exception.SessionConflictException;
import com.datagenerator.ai.exception.SessionNotFoundException;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Agent 会话应用服务：会话生命周期与对话编排入口。 */
public class AgentSessionApplicationService {

    private final AiProperties aiProperties;
    private final AgentOrchestrator orchestrator;
    private final ChatModelFactory chatModelFactory;
    private final ChatMemoryStore chatMemoryStore;
    private final AgentSessionRegistry sessionRegistry;
    private final AgentRoutingWorkflow routingWorkflow;
    private final AgentConversationWorkflow conversationWorkflow;

    public AgentSessionApplicationService(
            AiProperties aiProperties,
            AgentOrchestrator orchestrator,
            ChatModelFactory chatModelFactory,
            ChatMemoryStore chatMemoryStore,
            AgentSessionRegistry sessionRegistry,
            AgentRoutingWorkflow routingWorkflow,
            AgentConversationWorkflow conversationWorkflow) {
        this.aiProperties = aiProperties;
        this.orchestrator = orchestrator;
        this.chatModelFactory = chatModelFactory;
        this.chatMemoryStore = chatMemoryStore;
        this.sessionRegistry = sessionRegistry;
        this.routingWorkflow = routingWorkflow;
        this.conversationWorkflow = conversationWorkflow;
    }

    public List<AgentInfo> listAgents() {
        return orchestrator.listAgentIds().stream()
                .map(id -> WebDtoMapper.toAgentInfo(id, orchestrator.resolveToolSetIdForAgent(id)))
                .toList();
    }

    public List<ProviderInfo> listProviders() {
        return chatModelFactory.listProviderInfos();
    }

    /** SSE 长连接不设超时，避免 Tool 执行 + 模型推理超过固定时限后被断开。 */
    public Duration getStreamTimeout() {
        return Duration.ZERO;
    }

    public SessionSnapshot createSession(CreateSessionRequest request) {
        ensureProvidersAvailable();

        String agentId = resolveAgentId(request != null ? request.getAgentId() : null);
        if (!routingWorkflow.hasRuntime(agentId)) {
            throw new IllegalArgumentException("Unknown agent: " + agentId);
        }
        String toolSetId = orchestrator.resolveToolSetIdForAgent(agentId);

        String provider = resolveProvider(request != null ? request.getProvider() : null);
        chatModelFactory.getStreamingModel(provider);

        evictExpiredSessions();
        if (sessionRegistry.size() >= aiProperties.getSession().getMaxSessions()) {
            throw new IllegalStateException("Maximum session limit reached");
        }

        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AgentSession session = new AgentSession(sessionId, agentId, toolSetId, provider, now);
        sessionRegistry.put(session);

        return toSnapshot(session, false);
    }

    public SessionSnapshot getSession(String sessionId) {
        return getSession(sessionId, false);
    }

    public SessionSnapshot getSession(String sessionId, boolean includeDraft) {
        AgentSession session = requireSession(sessionId);
        return toSnapshot(session, includeDraft);
    }

    public String getSessionDraftYaml(String sessionId) {
        AgentSession session = requireSession(sessionId);
        return session.getDraftYaml();
    }

    public void deleteSession(String sessionId) {
        AgentSession session = sessionRegistry.remove(sessionId);
        if (session != null) {
            session.requestTurnCancellation();
            orchestrator.evictSession(session);
            session.endTurn();
        }
        chatMemoryStore.remove(sessionId);
    }

    public void sendMessage(String sessionId, String content, Consumer<SseEvent> emitter) {
        AgentSession session = requireSession(sessionId);
        if (!session.tryBeginTurn()) {
            throw new SessionConflictException(sessionId);
        }
        touchSession(session);
        try {
            conversationWorkflow.sendMessage(session, content, emitter);
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
        orchestrator.evictSession(session);
        session.endTurn();
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
            if (session.isTurnInProgress()) {
                session.requestTurnCancellation();
            }
            orchestrator.evictSession(session);
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
        if (chatModelFactory.availableProviders().isEmpty()) {
            throw new AiDisabledException("未配置可用的 AI Provider，请检查 ai.providers");
        }
    }

    private String resolveProvider(String requestedProvider) {
        String provider = StringUtils.hasText(requestedProvider)
                ? requestedProvider
                : aiProperties.getDefaultProvider();
        if (!StringUtils.hasText(provider)) {
            throw new AiDisabledException("未配置默认 AI Provider");
        }
        return provider;
    }

    private SessionSnapshot toSnapshot(AgentSession session, boolean includeDraft) {
        String draftYaml = session.getDraftYaml();
        boolean hasDraft = draftYaml != null && !draftYaml.isBlank();
        return new SessionSnapshot(
                session.getSessionId(),
                session.getAgentId(),
                session.getProvider(),
                session.getCreatedAt(),
                includeDraft ? draftYaml : null,
                hasDraft,
                session.isDraftIncomplete(),
                session.isDraftValidated());
    }
}
