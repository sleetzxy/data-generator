package com.datagenerator.ai.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateSessionRequest {

    @NotBlank
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
