package com.datagenerator.ai.application.session;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话注册表，供 SessionService 与 Tool 共享草稿 YAML。
 */
public class AgentSessionRegistry {

    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    public void put(AgentSession session) {
        sessions.put(session.getSessionId(), session);
    }

    public Optional<AgentSession> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public AgentSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }

    public void evictIfExpired(Instant cutoff, java.util.function.Consumer<AgentSession> beforeRemove) {
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().getLastActiveAt().isBefore(cutoff)) {
                beforeRemove.accept(entry.getValue());
                return true;
            }
            return false;
        });
    }

    public Optional<String> getDraftYaml(String sessionId) {
        return find(sessionId)
                .map(AgentSession::getDraftYaml)
                .filter(yaml -> yaml != null && !yaml.isBlank());
    }

    public void putReferenceYaml(String sessionId, String fileName, String yaml) {
        find(sessionId).ifPresent(session -> session.putReferenceYaml(fileName, yaml));
    }
}
