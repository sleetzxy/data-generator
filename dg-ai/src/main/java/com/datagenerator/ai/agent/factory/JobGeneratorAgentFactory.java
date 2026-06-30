package com.datagenerator.ai.agent.factory;

import com.datagenerator.ai.agent.instances.JobGeneratorAgent;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.prompt.AgentPrompt;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.Map;

/** 装配 Job 生成 Agent：模型、Tool Set、Memory、Prompt。 */
public final class JobGeneratorAgentFactory {

    private final AgentIoLogger ioLogger;

    public JobGeneratorAgentFactory(AgentIoLogger ioLogger) {
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
    }

    public JobGeneratorAgent create(
            String agentId,
            String provider,
            AiProperties aiProperties,
            ChatMemoryStore chatMemoryStore,
            AgentPrompt promptProvider,
            Map<ToolSpecification, ToolExecutor> toolExecutors) {
        return AiServices.builder(JobGeneratorAgent.class)
                .streamingChatModel(aiProperties.createStreamingModel(provider))
                .tools(toolExecutors)
                .chatMemoryProvider(id -> chatMemoryStore.getOrCreate(agentId, (String) id))
                .systemMessageProvider(id -> promptProvider.loadSystemPrompt(agentId))
                .build();
    }
}
