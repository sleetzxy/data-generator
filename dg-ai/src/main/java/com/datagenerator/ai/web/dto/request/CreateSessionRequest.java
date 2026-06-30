package com.datagenerator.ai.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public class CreateSessionRequest {

    /** 必填；须为已注册的 Agent id */
    @NotBlank(message = "agentId 不能为空")
    private String agentId;
    /** 可选；未指定时使用 Agent 配置的 provider */
    private String provider;

    public CreateSessionRequest() {
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
}
