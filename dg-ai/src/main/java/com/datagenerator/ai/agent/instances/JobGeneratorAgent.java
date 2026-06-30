package com.datagenerator.ai.agent.instances;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/** Job YAML 生成 Agent 实例契约。 */
public interface JobGeneratorAgent {

    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
