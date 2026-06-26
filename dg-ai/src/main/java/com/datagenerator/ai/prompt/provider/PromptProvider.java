package com.datagenerator.ai.prompt.provider;

/**
 * 为 Agent 提供系统提示词；内容来自 {@code prompt/templates/{agentId}/}，不在 Agent 接口上硬编码。
 */
public interface PromptProvider {

    String resolveSystemPrompt(String agentId);
}
