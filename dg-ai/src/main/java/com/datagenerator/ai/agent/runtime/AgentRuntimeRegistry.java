package com.datagenerator.ai.agent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentRuntimeRegistry {

    private final Map<String, AgentRuntime> runtimes = new LinkedHashMap<>();

    public AgentRuntimeRegistry(List<AgentRuntime> runtimes) {
        for (AgentRuntime runtime : runtimes) {
            AgentRuntime previous = this.runtimes.put(runtime.agentId(), runtime);
            if (previous != null) {
                throw new IllegalStateException("Duplicate AgentRuntime for agent: " + runtime.agentId());
            }
        }
    }

    public AgentRuntime require(String agentId) {
        AgentRuntime runtime = runtimes.get(agentId);
        if (runtime == null) {
            throw new IllegalArgumentException("No runtime registered for agent: " + agentId);
        }
        return runtime;
    }

    public boolean hasRuntime(String agentId) {
        return runtimes.containsKey(agentId);
    }

    public List<String> listAgentIds() {
        return List.copyOf(runtimes.keySet());
    }
}
