package com.datagenerator.ai.agent.orchestrator;

import com.datagenerator.ai.agent.runtime.AgentExecutionContext;
import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.exception.AiDisabledException;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.model.adapter.ChatModelFactory;
import com.datagenerator.ai.prompt.provider.PromptProvider;
import com.datagenerator.ai.tool.registry.ToolRegistry;
import org.springframework.util.StringUtils;
import java.util.List;

/** Agent 调度器：按配置解析 Tool Set 能力边界，并构造单次执行上下文。 */
public class AgentOrchestrator {

    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final ToolRegistry toolRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ChatMemoryStore chatMemoryStore;
    private final PromptProvider promptProvider;
    private final AiProperties aiProperties;

    public AgentOrchestrator(
            AgentRuntimeRegistry agentRuntimeRegistry,
            ToolRegistry toolRegistry,
            ChatModelFactory chatModelFactory,
            ChatMemoryStore chatMemoryStore,
            PromptProvider promptProvider,
            AiProperties aiProperties) {
        this.agentRuntimeRegistry = agentRuntimeRegistry;
        this.toolRegistry = toolRegistry;
        this.chatModelFactory = chatModelFactory;
        this.chatMemoryStore = chatMemoryStore;
        this.promptProvider = promptProvider;
        this.aiProperties = aiProperties;
    }

    public AgentRuntime requireRuntime(String agentId) {
        return agentRuntimeRegistry.require(agentId);
    }

    public boolean hasRuntime(String agentId) {
        return agentRuntimeRegistry.hasRuntime(agentId);
    }

    /** 读取 Agent 配置的 Tool Set（声明可用 Tool 集合，非运行时选 Tool）。 */
    public String resolveToolSetIdForAgent(String agentId) {
        requireRuntime(agentId);
        String toolSetId = requireAgentProperties(agentId).getToolSetId().trim();
        if (!toolRegistry.hasToolSet(toolSetId)) {
            throw new IllegalArgumentException("Unknown tool set: " + toolSetId);
        }
        return toolSetId;
    }

    public AgentExecutionContext createContext(AgentSession session) {
        return new AgentExecutionContext(
                session.getProvider(),
                session.getAgentId(),
                session.getToolSetId(),
                chatModelFactory,
                chatMemoryStore,
                promptProvider);
    }

    public void evictSession(AgentSession session) {
        agentRuntimeRegistry.require(session.getAgentId()).evictSession(session.getSessionId());
    }

    public List<String> listAgentIds() {
        return agentRuntimeRegistry.listAgentIds();
    }

    private AiProperties.AgentProperties requireAgentProperties(String agentId) {
        AiProperties.AgentProperties configured = aiProperties.getAgents().get(agentId);
        if (configured == null) {
            throw new AiDisabledException("未配置 Agent: " + agentId);
        }
        if (!StringUtils.hasText(configured.getToolSetId())) {
            throw new AiDisabledException("Agent 未配置 tool-set-id: " + agentId);
        }
        return configured;
    }
}
