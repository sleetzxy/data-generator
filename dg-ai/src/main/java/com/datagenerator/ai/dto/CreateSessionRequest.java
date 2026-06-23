package com.datagenerator.ai.dto;

public class CreateSessionRequest {

    private String skillId;
    private String provider;

    public CreateSessionRequest() {
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
}
