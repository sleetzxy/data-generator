package com.datagenerator.ai.memory;

import com.datagenerator.ai.config.AiProperties;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的会话记忆存储。
 * <p>支持按 agentId 选择压缩策略和记忆参数；
 * 未指定时回退到全局默认配置。
 */
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final AiProperties properties;
    private final TokenCountEstimator tokenCountEstimator;
    private final ChatMemoryContentCompressor defaultCompressor;
    private final Map<String, ChatMemoryContentCompressor> compressorByAgent;
    private final ConcurrentHashMap<String, MemoryEntry> memories = new ConcurrentHashMap<>();

    public InMemoryChatMemoryStore(AiProperties properties, ChatMemoryContentCompressor defaultCompressor) {
        this(properties, defaultCompressor, Collections.emptyMap());
    }

    /**
     * @param properties       全局 AI 配置
     * @param defaultCompressor 未匹配到 Agent 专属压缩器时的默认压缩策略
     * @param compressorByAgent 按 agentId 映射的压缩策略（可为空）
     */
    public InMemoryChatMemoryStore(AiProperties properties,
                                   ChatMemoryContentCompressor defaultCompressor,
                                   Map<String, ChatMemoryContentCompressor> compressorByAgent) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
        this.defaultCompressor = Objects.requireNonNull(defaultCompressor, "defaultCompressor must not be null");
        this.compressorByAgent = Map.copyOf(compressorByAgent);
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

    /** 向后兼容：使用默认压缩策略（适用于不需要 agentId 区分的历史调用点） */
    public ChatMemory getOrCreate(String sessionId) {
        return getOrCreate("", sessionId);
    }

    @Override
    public void compact(String sessionId) {
        MemoryEntry entry = memories.get(sessionId);
        if (entry == null) {
            return;
        }
        if (entry.memory instanceof SummarizingChatMemory summarizing) {
            summarizing.compactExisting();
        }
    }

    private ChatMemory createMemory(String agentId, String sessionId) {
        AiProperties.AgentProperties agentProps = properties.getAgents().get(agentId);
        int maxTokens = resolveInt(agentProps != null ? agentProps.getChatMemoryMaxTokens() : null,
                properties.getChatMemoryMaxTokens());
        int maxMessages = resolveInt(agentProps != null ? agentProps.getChatMemoryMaxMessages() : null,
                properties.getChatMemoryMaxMessages());
        int toolResultMaxChars = resolveInt(
                agentProps != null ? agentProps.getChatMemoryToolResultMaxChars() : null,
                properties.getChatMemoryToolResultMaxChars());
        ChatMemoryContentCompressor compressor = compressorByAgent.getOrDefault(agentId, defaultCompressor);

        ChatMemory delegate;
        if (maxTokens > 0) {
            delegate = TokenWindowChatMemory.builder()
                    .id(sessionId)
                    .maxTokens(maxTokens, tokenCountEstimator)
                    .build();
        } else {
            delegate = MessageWindowChatMemory.builder()
                    .id(sessionId)
                    .maxMessages(maxMessages)
                    .build();
        }
        return new SummarizingChatMemory(sessionId, delegate, toolResultMaxChars, compressor);
    }

    private static int resolveInt(Integer override, int fallback) {
        return override != null ? override : fallback;
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
