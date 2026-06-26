package com.datagenerator.ai.web.dto.response;

import java.time.Instant;

public class SessionResponse {

    private String sessionId;
    private String agentId;
    private String provider;
    private Instant createdAt;
    private String draftYaml;
    private boolean hasDraft;
    private boolean draftIncomplete;
    private boolean draftValidated;

    public SessionResponse() {
    }

    public SessionResponse(
            String sessionId,
            String agentId,
            String provider,
            Instant createdAt,
            String draftYaml,
            boolean hasDraft,
            boolean draftIncomplete,
            boolean draftValidated) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.provider = provider;
        this.createdAt = createdAt;
        this.draftYaml = draftYaml;
        this.hasDraft = hasDraft;
        this.draftIncomplete = draftIncomplete;
        this.draftValidated = draftValidated;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
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

    public boolean isHasDraft() {
        return hasDraft;
    }

    public void setHasDraft(boolean hasDraft) {
        this.hasDraft = hasDraft;
    }

    public boolean isDraftIncomplete() {
        return draftIncomplete;
    }

    public void setDraftIncomplete(boolean draftIncomplete) {
        this.draftIncomplete = draftIncomplete;
    }

    public boolean isDraftValidated() {
        return draftValidated;
    }

    public void setDraftValidated(boolean draftValidated) {
        this.draftValidated = draftValidated;
    }
}
