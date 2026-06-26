package com.datagenerator.ai.memory;

import com.datagenerator.ai.config.AiProperties;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final AiProperties properties;
    private final TokenCountEstimator tokenCountEstimator;
    private final ChatMemoryContentCompressor compressor;
    private final ConcurrentHashMap<String, MemoryEntry> memories = new ConcurrentHashMap<>();

    public InMemoryChatMemoryStore(AiProperties properties, ChatMemoryContentCompressor compressor) {
        this.properties = properties;
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
        this.compressor = compressor;
    }

    @Override
    public ChatMemory getOrCreate(String sessionId) {
        return memories.compute(sessionId, (id, existing) -> {
            Instant now = Instant.now();
            if (existing == null) {
                ChatMemory memory = createMemory(id);
                return new MemoryEntry(memory, now);
            }
            existing.lastActiveAt = now;
            return existing;
        }).memory;
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

    private ChatMemory createMemory(String sessionId) {
        ChatMemory delegate;
        if (properties.getChatMemoryMaxTokens() > 0) {
            delegate = TokenWindowChatMemory.builder()
                    .id(sessionId)
                    .maxTokens(properties.getChatMemoryMaxTokens(), tokenCountEstimator)
                    .build();
        } else {
            delegate = MessageWindowChatMemory.builder()
                    .id(sessionId)
                    .maxMessages(properties.getChatMemoryMaxMessages())
                    .build();
        }
        return new SummarizingChatMemory(
                sessionId, delegate, properties.getChatMemoryToolResultMaxChars(), compressor);
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
