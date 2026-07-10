package com.datagenerator.ai.dto;

import java.util.List;

/**
 * 会话消息列表 DTO，用于会话消息查询接口返回。
 *
 * <p>消息按 block 级别结构化，每个 block 包含 {@code type} 字段：
 * {@code text} / {@code thinking} / {@code tool_call} / {@code tool_result}。
 */
public record SessionMessages(
        String chatId,
        List<MessageItem> messages) {

    /**
     * 单条消息项（角色 + 结构化 blocks）。
     */
    public record MessageItem(String role, List<Block> blocks) {}

    /**
     * 消息内容块，所有子类型均包含显式 {@code type} 字段用于前端区分。
     */
    public interface Block {
        String type();
    }

    /** 文本块。 */
    public record TextBlock(String type, String text) implements Block {
        public TextBlock(String text) {
            this("text", text);
        }
    }

    /** 思考过程块。 */
    public record ThinkingBlock(String type, String thinking) implements Block {
        public ThinkingBlock(String thinking) {
            this("thinking", thinking);
        }
    }

    /** 工具调用块。 */
    public record ToolCallBlock(String type, String toolCallId, String toolName) implements Block {
        public ToolCallBlock(String toolCallId, String toolName) {
            this("tool_call", toolCallId, toolName);
        }
    }

    /** 工具调用结果块。 */
    public record ToolResultBlock(String type, String toolCallId, String text) implements Block {
        public ToolResultBlock(String toolCallId, String text) {
            this("tool_result", toolCallId, text);
        }
    }
}
