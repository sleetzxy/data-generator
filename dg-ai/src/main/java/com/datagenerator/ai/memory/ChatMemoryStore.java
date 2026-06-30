package com.datagenerator.ai.memory;

import dev.langchain4j.memory.ChatMemory;

/**
 * 会话记忆存储，按 agentId + sessionId 存取。
 * <p>agentId 用于选择正确的记忆配置（maxTokens/maxMessages 等），
 * 不同 Agent 可共享同一存储实例但使用不同参数。
 */
public interface ChatMemoryStore {

    /** 获取或创建会话记忆，agentId 用于选择对应的记忆配置 */
    ChatMemory getOrCreate(String agentId, String sessionId);

    void remove(String sessionId);

    void touch(String sessionId);
}
