package com.datagenerator.ai.application.workflow;

import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.application.session.AgentSession;

/**
 * 理解用户意图并路由到具体 Agent。当前按会话绑定的 agentId 路由，后续可扩展多 Agent 选择逻辑。
 */
public class AgentRoutingWorkflow {

    private final AgentRuntimeRegistry agentRuntimeRegistry;

    public AgentRoutingWorkflow(AgentRuntimeRegistry agentRuntimeRegistry) {
        this.agentRuntimeRegistry = agentRuntimeRegistry;
    }

    public String resolveAgentId(AgentSession session, String userMessage) {
        String agentId = session.getAgentId();
        if (!agentRuntimeRegistry.hasRuntime(agentId)) {
            throw new IllegalStateException("No runtime registered for agent: " + agentId);
        }
        return agentId;
    }

    public boolean hasRuntime(String agentId) {
        return agentRuntimeRegistry.hasRuntime(agentId);
    }
}
