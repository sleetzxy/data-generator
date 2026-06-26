package com.datagenerator.ai.tool.interceptor;

/** Tool 返回文本规范化，避免 LangChain4j 因 null/空串拒绝写入 ToolExecutionResultMessage。 */
public final class ToolResultTexts {

    private ToolResultTexts() {
    }

    public static String ensureNonBlank(String result, String toolName) {
        if (result != null && !result.isBlank()) {
            return result;
        }
        return "["
                + toolName
                + " 未返回有效结果；请重试或检查 dg-web 连接与服务认证 token]";
    }
}
