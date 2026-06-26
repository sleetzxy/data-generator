package com.datagenerator.ai.memory;

import dev.langchain4j.memory.ChatMemory;

public interface ChatMemoryStore {

    ChatMemory getOrCreate(String sessionId);

    void remove(String sessionId);

    void touch(String sessionId);

    /** 压缩已有对话记忆中的大段 YAML，完整内容保留在会话草稿。 */
    void compact(String sessionId);
}
