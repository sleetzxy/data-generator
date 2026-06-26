package com.datagenerator.ai.tool.interceptor;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/** 单次 Tool 调用的拦截上下文。 */
public record ToolExecutionContext(
        String sessionId,
        String toolName,
        String arguments,
        ToolExecutionRequest request,
        Object memoryId) {
}
