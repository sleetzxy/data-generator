package com.datagenerator.ai.application;

import com.datagenerator.ai.config.AiProperties;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 对话与 Tool 调用的输入/输出日志，便于排查分步输出与 YAML 收敛问题。
 * 由 {@code ai.io-logging.enabled} 控制开关。
 */
public class AgentIoLogger {

    private static final Logger log = LoggerFactory.getLogger(AgentIoLogger.class);

    private final AiProperties.IoLoggingProperties config;

    public AgentIoLogger(AiProperties properties) {
        this.config = properties.getIoLogging();
    }

    public static AgentIoLogger disabled() {
        AiProperties properties = new AiProperties();
        properties.getIoLogging().setEnabled(false);
        return new AgentIoLogger(properties);
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public void logModelInput(String sessionId, String source, int streamAttempt, String content) {
        if (!config.isEnabled()) {
            return;
        }
        log.info(
                "\n========== [Agent IO] session={} >>> 模型输入 ({}, attempt={}) ==========\n{}\n"
                        + "========== [Agent IO] 输入结束 ({} 字符) ==========",
                sessionId,
                source,
                streamAttempt,
                clip(content),
                charCount(content));
    }

    public void logModelOutput(String sessionId, ChatResponse response, String fullText) {
        if (!config.isEnabled()) {
            return;
        }
        FinishReason finishReason = response != null ? response.finishReason() : null;
        String tokenUsage = formatTokenUsage(response);
        boolean hasStructuredOutput = fullText != null
                && (fullText.contains("```yaml") || fullText.contains("```yml"));
        log.info(
                "\n========== [Agent IO] session={} <<< 模型输出 (finishReason={}, {}, structured={}) ==========\n{}\n"
                        + "========== [Agent IO] 输出结束 ({} 字符) ==========",
                sessionId,
                finishReason,
                tokenUsage,
                hasStructuredOutput,
                clip(fullText),
                charCount(fullText));
    }

    public void logToolCall(String sessionId, String toolName, String arguments, String result) {
        if (!config.isEnabled()) {
            return;
        }
        log.info(
                "\n---------- [Agent IO] session={} TOOL {} ----------\n>>> 参数:\n{}\n<<< 返回:\n{}\n"
                        + "---------- [Agent IO] TOOL 结束 (参数 {} 字符, 返回 {} 字符) ----------",
                sessionId,
                toolName,
                clip(arguments),
                config.isLogToolResults() ? clip(result) : "(未记录返回体, ai.io-logging.log-tool-results=false)",
                charCount(arguments),
                charCount(result));
    }

    public void logSseToClient(String sessionId, String eventType, String data) {
        if (!config.isEnabled() || !config.isLogSseEvents()) {
            return;
        }
        log.info(
                "[Agent IO] session={} SSE >>> {} : {}",
                sessionId,
                eventType,
                clip(data));
    }

    private String clip(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        int maxChars = config.getMaxChars();
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int head = Math.max(256, maxChars * 2 / 3);
        int tail = Math.max(128, maxChars - head - 64);
        if (head + tail >= text.length()) {
            return text;
        }
        int omitted = text.length() - head - tail;
        return text.substring(0, head)
                + "\n... [省略 "
                + omitted
                + " 字符, 完整长度 "
                + text.length()
                + "] ...\n"
                + text.substring(text.length() - tail);
    }

    private static int charCount(String text) {
        return text == null ? 0 : text.length();
    }

    private static String formatTokenUsage(ChatResponse response) {
        if (response == null || response.tokenUsage() == null) {
            return "tokens=未知";
        }
        var usage = response.tokenUsage();
        return "inputTokens="
                + usage.inputTokenCount()
                + ", outputTokens="
                + usage.outputTokenCount()
                + ", total="
                + usage.totalTokenCount();
    }
}
