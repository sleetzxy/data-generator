package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.model.adapter.ChatModelFactory;
import com.datagenerator.ai.prompt.provider.PromptProvider;

/** 单次 Agent 执行所需的共享依赖与会话绑定信息。 */
public record AgentExecutionContext(
        String provider,
        String agentId,
        String toolSetId,
        ChatModelFactory chatModelFactory,
        ChatMemoryStore chatMemoryStore,
        PromptProvider promptProvider) {}
