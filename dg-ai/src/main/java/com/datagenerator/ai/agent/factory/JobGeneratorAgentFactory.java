package com.datagenerator.ai.agent.factory;

import com.datagenerator.ai.agent.instances.JobGeneratorAgent;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.model.adapter.ChatModelFactory;
import com.datagenerator.ai.prompt.provider.PromptProvider;
import com.datagenerator.ai.agent.runtime.AgentExecutionContext;
import com.datagenerator.ai.tool.registry.ToolRegistry;
import dev.langchain4j.service.AiServices;

/** 装配 Job 生成 Agent：模型、Tool Set、Memory、Prompt。 */
public final class JobGeneratorAgentFactory {

    private final ToolRegistry toolRegistry;
    private final AgentIoLogger ioLogger;

    public JobGeneratorAgentFactory(ToolRegistry toolRegistry, AgentIoLogger ioLogger) {
        this.toolRegistry = toolRegistry;
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
    }

    public JobGeneratorAgent create(AgentExecutionContext context) {
        PromptProvider promptProvider = context.promptProvider();
        ChatModelFactory chatModelFactory = context.chatModelFactory();
        ChatMemoryStore chatMemoryStore = context.chatMemoryStore();
        return AiServices.builder(JobGeneratorAgent.class)
                .streamingChatModel(chatModelFactory.getStreamingModel(context.provider()))
                .tools(toolRegistry.require(context.toolSetId()).createExecutors(ioLogger))
                .chatMemoryProvider(id -> chatMemoryStore.getOrCreate(context.agentId(), (String) id))
                .systemMessageProvider(id -> promptProvider.resolveSystemPrompt(context.agentId()))
                .build();
    }
}
