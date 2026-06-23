package com.datagenerator.ai.session;

import dev.langchain4j.memory.ChatMemory;

public interface ChatMemoryStore {

    ChatMemory getOrCreate(String sessionId);

    void remove(String sessionId);

    void touch(String sessionId);
}
