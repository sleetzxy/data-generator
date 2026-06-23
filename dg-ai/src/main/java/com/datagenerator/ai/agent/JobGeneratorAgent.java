package com.datagenerator.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface JobGeneratorAgent {

    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
