package com.datagenerator.ai.web;

import com.datagenerator.ai.application.dto.SessionSnapshot;
import com.datagenerator.ai.web.dto.response.AgentInfo;
import com.datagenerator.ai.web.dto.response.ProviderInfo;
import com.datagenerator.ai.web.dto.response.SessionResponse;

/** Web 层 DTO 与业务层对象之间的映射。 */
public final class WebDtoMapper {

    private WebDtoMapper() {
    }

    public static SessionResponse toSessionResponse(SessionSnapshot snapshot) {
        return new SessionResponse(
                snapshot.sessionId(),
                snapshot.agentId(),
                snapshot.provider(),
                snapshot.createdAt(),
                snapshot.draftYaml(),
                snapshot.hasDraft(),
                snapshot.draftIncomplete(),
                snapshot.draftValidated());
    }

    public static AgentInfo toAgentInfo(String id, String toolSetId) {
        return new AgentInfo(id, toolSetId);
    }

    public static ProviderInfo toProviderInfo(
            String id, String label, String model, boolean defaultProvider) {
        return new ProviderInfo(id, label, model, defaultProvider);
    }
}
