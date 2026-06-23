package com.datagenerator.ai.session;

import java.time.Instant;

public class AgentSession {

    private final String sessionId;
    private final String skillId;
    private final String provider;
    private final Instant createdAt;
    private Instant lastActiveAt;
    private String draftYaml;

    public AgentSession(String sessionId, String skillId, String provider, Instant createdAt) {
        this.sessionId = sessionId;
        this.skillId = skillId;
        this.provider = provider;
        this.createdAt = createdAt;
        this.lastActiveAt = createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSkillId() {
        return skillId;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public String getDraftYaml() {
        return draftYaml;
    }

    public void setDraftYaml(String draftYaml) {
        this.draftYaml = draftYaml;
    }
}
