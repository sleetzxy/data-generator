package com.datagenerator.ai.memory;

/**
 * 对话记忆内容压缩策略。
 * <p>不同 Agent 可提供各自的实现来控制 Tool 结果和对话文本的压缩方式。
 * 后续新增 Agent 时，只需实现此接口并将 Bean 注入记忆层即可。
 */
public interface ChatMemoryContentCompressor {

    /** 压缩对话文本中的结构化内容（JSON/YAML 代码块等） */
    String compressConversationText(String text);

    /** 压缩 Tool 执行结果文本 */
    String compressToolResult(String toolName, String result, int toolResultMaxChars);
}
