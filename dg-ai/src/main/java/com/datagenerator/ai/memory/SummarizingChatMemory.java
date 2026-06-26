package com.datagenerator.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import java.util.ArrayList;
import java.util.List;

/**
 * 写入 ChatMemory 前压缩大段 YAML，完整内容存于会话草稿 / 参考缓存。
 * <p>压缩策略由 {@link ChatMemoryContentCompressor} 提供，不同 Agent 可注入不同实现。
 */
public final class SummarizingChatMemory implements ChatMemory {

    private final Object id;
    private final ChatMemory delegate;
    private final int toolResultMaxChars;
    private final ChatMemoryContentCompressor compressor;

    public SummarizingChatMemory(Object id, ChatMemory delegate, int toolResultMaxChars,
                                 ChatMemoryContentCompressor compressor) {
        this.id = id;
        this.delegate = delegate;
        this.toolResultMaxChars = toolResultMaxChars;
        this.compressor = compressor;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(compress(message));
    }

    @Override
    public List<ChatMessage> messages() {
        return delegate.messages();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    /** 重写已有消息，确保历史轮次中的大 YAML 也被压缩。 */
    public void compactExisting() {
        List<ChatMessage> existing = new ArrayList<>(delegate.messages());
        delegate.clear();
        for (ChatMessage message : existing) {
            delegate.add(compress(message));
        }
    }

    private ChatMessage compress(ChatMessage message) {
        if (message instanceof AiMessage aiMessage) {
            String text = aiMessage.text();
            if (text == null || text.isBlank()) {
                return message;
            }
            String compressed = compressor.compressConversationText(text);
            if (compressed.equals(text)) {
                return message;
            }
            if (aiMessage.hasToolExecutionRequests()) {
                return AiMessage.from(compressed, aiMessage.toolExecutionRequests());
            }
            return AiMessage.from(compressed);
        }
        if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
            String compressed = compressor.compressConversationText(
                    userMessage.singleText());
            if (compressed.equals(userMessage.singleText())) {
                return message;
            }
            return UserMessage.from(compressed);
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            String text = toolResult.text();
            if (text == null) {
                return message;
            }
            String compressed = compressor.compressToolResult(
                    toolResult.toolName(), text, toolResultMaxChars);
            if (compressed.equals(text)) {
                return message;
            }
            return ToolExecutionResultMessage.from(
                    toolResult.id(), toolResult.toolName(), compressed);
        }
        return message;
    }
}
