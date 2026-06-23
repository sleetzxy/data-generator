package com.datagenerator.ai.dto;

import java.time.Instant;

public class SessionResponse {

    private String sessionId;
    private String skillId;
    private String provider;
    private Instant createdAt;
    private String draftYaml;

    public SessionResponse() {
    }

    public SessionResponse(
            String sessionId,
            String skillId,
            String provider,
            Instant createdAt,
            String draftYaml) {
        this.sessionId = sessionId;
        this.skillId = skillId;
        this.provider = provider;
        this.createdAt = createdAt;
        this.draftYaml = draftYaml;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDraftYaml() {
        return draftYaml;
    }

    public void setDraftYaml(String draftYaml) {
        this.draftYaml = draftYaml;
    }
}
