package com.datagenerator.ai.util;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

/**
 * 解析模型回复的 finish reason，用于判断输出是否因长度上限被截断。
 */
public final class ResponseFinishReasons {

    private ResponseFinishReasons() {
    }

    public static boolean isLengthTruncated(ChatResponse response) {
        if (response == null) {
            return false;
        }
        FinishReason reason = response.finishReason();
        if (reason == FinishReason.LENGTH) {
            return true;
        }
        if (response.metadata() != null && response.metadata().finishReason() == FinishReason.LENGTH) {
            return true;
        }
        return false;
    }
}
