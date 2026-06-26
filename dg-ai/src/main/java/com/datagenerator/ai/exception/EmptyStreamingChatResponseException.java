package com.datagenerator.ai.exception;

/** 流式模型在 onComplete 时未返回有效 {@code ChatResponse}（LangChain4j 已知问题）。 */
public class EmptyStreamingChatResponseException extends RuntimeException {

    public EmptyStreamingChatResponseException() {
        super("模型流式响应为空，未返回文本或工具调用结果");
    }
}
