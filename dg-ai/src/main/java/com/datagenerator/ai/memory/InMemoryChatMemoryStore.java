package com.datagenerator.ai.memory;

import com.datagenerator.ai.config.AiProperties;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的会话记忆存储。
 * <p>支持按 agentId 选择记忆参数；未指定时回退到全局默认配置。
 */
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private static final int DEFAULT_CHAT_MEMORY_MAX_TOKENS = 48_000;
    private static final int DEFAULT_CHAT_MEMORY_MAX_MESSAGES = 40;

    private final AiProperties properties;
    private final TokenCountEstimator tokenCountEstimator;
    private final ConcurrentHashMap<String, MemoryEntry> memories = new ConcurrentHashMap<>();

    public InMemoryChatMemoryStore(AiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
    }

    @Override
    public ChatMemory getOrCreate(String agentId, String sessionId) {
        return memories.compute(sessionId, (id, existing) -> {
            Instant now = Instant.now();
            if (existing == null) {
                ChatMemory memory = createMemory(agentId, id);
                return new MemoryEntry(memory, now);
            }
            existing.lastActiveAt = now;
            return existing;
        }).memory;
    }

    /** 向后兼容：使用默认配置（适用于不需要 agentId 区分的历史调用点） */
    public ChatMemory getOrCreate(String sessionId) {
        return getOrCreate("", sessionId);
    }

    private ChatMemory createMemory(String agentId, String sessionId) {
        int maxTokens = DEFAULT_CHAT_MEMORY_MAX_TOKENS;
        int maxMessages = DEFAULT_CHAT_MEMORY_MAX_MESSAGES;
        if (maxTokens > 0) {
            return TokenWindowChatMemory.builder()
                    .id(sessionId)
                    .maxTokens(maxTokens, tokenCountEstimator)
                    .build();
        }
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(maxMessages)
                .build();
    }

    @Override
    public void remove(String sessionId) {
        memories.remove(sessionId);
    }

    @Override
    public void touch(String sessionId) {
        MemoryEntry entry = memories.get(sessionId);
        if (entry != null) {
            entry.lastActiveAt = Instant.now();
        }
    }

    private static final class MemoryEntry {
        private final ChatMemory memory;
        private Instant lastActiveAt;

        private MemoryEntry(ChatMemory memory, Instant lastActiveAt) {
            this.memory = memory;
            this.lastActiveAt = lastActiveAt;
        }
    }
}
