package com.datagenerator.ai.tool.interceptor;

import com.fasterxml.jackson.core.JsonParseException;

/**
 * 识别 LangChain4j 解析 Tool 参数 JSON 时的截断/格式错误。
 */
public final class ToolArgumentErrors {

    public static final String TRUNCATED_ARGS_FEEDBACK =
            "错误：工具参数 JSON 不完整（YAML 过长导致被模型截断）。"
                    + "请先将完整 YAML 写入结构化 JSON 的 draftYaml 字段，"
                    + "再调用 validateDraftJobYaml、previewDraftJobYaml、saveDraftJobDefinition 或 submitDraftJob，"
                    + "勿在 validateJobYaml、previewJobYaml、submitJob、createJobDefinition 的参数中传入整段 YAML。";

    /** 流式重试时注入的纠偏提示，避免模型重复相同截断错误 */
    public static final String STREAM_RETRY_HINT =
            "【系统纠偏】上次 Tool 调用参数 JSON 被截断。"
                    + "请勿在 Tool 参数中传入整段 YAML；"
                    + "请使用结构化 JSON 输出 draftYaml，再调用 validateDraftJobYaml 等 Draft 系列 Tool。";

    private ToolArgumentErrors() {
    }

    public static boolean isTruncatedArguments(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JsonParseException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("jsoneofexception")
                        || lower.contains("end-of-input")
                        || lower.contains("expecting closing quote")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isContextOverflow(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("maximum context")
                        || lower.contains("context length")
                        || lower.contains("context window")
                        || lower.contains("token limit")
                        || lower.contains("too many tokens")
                        || lower.contains("reduce the length")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
