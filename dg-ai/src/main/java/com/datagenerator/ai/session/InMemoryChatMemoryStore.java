package com.datagenerator.ai.session;

import com.datagenerator.ai.config.AiProperties;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final AiProperties properties;
    private final ConcurrentHashMap<String, MemoryEntry> memories = new ConcurrentHashMap<>();

    public InMemoryChatMemoryStore(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChatMemory getOrCreate(String sessionId) {
        evictExpired();
        return memories.compute(sessionId, (id, existing) -> {
            Instant now = Instant.now();
            if (existing == null) {
                ChatMemory memory = MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(properties.getChatMemoryMaxMessages())
                        .build();
                return new MemoryEntry(memory, now);
            }
            existing.lastActiveAt = now;
            return existing;
        }).memory;
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

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(properties.getSession().getTtl());
        memories.entrySet().removeIf(entry -> entry.getValue().lastActiveAt.isBefore(cutoff));
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
