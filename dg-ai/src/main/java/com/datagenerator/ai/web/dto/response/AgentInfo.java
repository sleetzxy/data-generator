package com.datagenerator.ai.web.dto.response;

public class AgentInfo {

    private final String id;
    private final String toolSetId;

    public AgentInfo(String id, String toolSetId) {
        this.id = id;
        this.toolSetId = toolSetId;
    }

    public String getId() {
        return id;
    }

    public String getToolSetId() {
        return toolSetId;
    }
}
