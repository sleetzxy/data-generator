package com.datagenerator.ai.application.workflow;

import com.datagenerator.ai.agent.result.DraftResultParser;

/**
 * 从结构化 JSON 流式回复中提取 {@code message} 字段，避免将 {@code draftYaml} 等内部字段推给前端。
 */
public final class StructuredOutputMessageFilter {

    private static final String JSON_FENCE = "```json";
    private static final int PLAIN_TEXT_THRESHOLD = 32;

    private final StringBuilder accumulated = new StringBuilder();
    private int emittedMessageLength;
    private boolean passthrough;

    public String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        if (passthrough) {
            return delta;
        }
        accumulated.append(delta);
        String text = accumulated.toString();
        if (!containsJsonFence(text) && text.length() >= PLAIN_TEXT_THRESHOLD) {
            passthrough = true;
            accumulated.setLength(0);
            emittedMessageLength = 0;
            return text;
        }
        String message = DraftResultParser.extractPartialMessage(text);
        if (message == null) {
            return "";
        }
        if (message.length() <= emittedMessageLength) {
            return "";
        }
        String chunk = message.substring(emittedMessageLength);
        emittedMessageLength = message.length();
        return chunk;
    }

    /** 无结构化 JSON 时，将缓冲的纯文本一次性刷出。 */
    public String flush() {
        if (passthrough || accumulated.isEmpty()) {
            return "";
        }
        String text = accumulated.toString();
        accumulated.setLength(0);
        if (containsJsonFence(text)) {
            return "";
        }
        passthrough = true;
        emittedMessageLength = 0;
        return text;
    }

    /** 流式结束后，根据完整回复补发尚未推送的 {@code message} 片段。 */
    public String reconcileFullText(String fullText) {
        String trailing = flush();
        if (fullText == null || fullText.isBlank()) {
            return trailing;
        }
        if (passthrough) {
            return trailing;
        }
        String message = DraftResultParser.extractPartialMessage(fullText);
        if (message == null) {
            return trailing;
        }
        if (message.length() <= emittedMessageLength) {
            return trailing;
        }
        String chunk = message.substring(emittedMessageLength);
        emittedMessageLength = message.length();
        if (trailing.isEmpty()) {
            return chunk;
        }
        return trailing + chunk;
    }

    private static boolean containsJsonFence(String text) {
        return text != null && text.toLowerCase().contains(JSON_FENCE);
    }
}
