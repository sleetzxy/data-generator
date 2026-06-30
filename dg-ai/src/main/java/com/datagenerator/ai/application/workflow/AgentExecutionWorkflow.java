package com.datagenerator.ai.application.workflow;

import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.sse.SseEvent;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.prompt.AgentPrompt;
import com.fasterxml.jackson.core.JsonParseException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 执行已路由 Agent 的流式对话与结构化输出续写。 */
public class AgentExecutionWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionWorkflow.class);
    private static final int MAX_STREAM_ATTEMPTS = 3;
    private static final String EMPTY_RESPONSE_MARKER = "EMPTY_STREAMING_RESPONSE";

    private static final String TRUNCATED_ARGS_FEEDBACK =
            "工具参数 JSON 不完整（参数过长导致被截断），请缩短参数内容后重试";

    private static final String STREAM_RETRY_HINT =
            "【系统纠偏】上次 Tool 调用参数 JSON 被截断，请缩短 Tool 参数后重试";

    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final AiProperties aiProperties;
    private final ChatMemoryStore chatMemoryStore;
    private final AgentPrompt promptProvider;
    private final AgentIoLogger ioLogger;

    public AgentExecutionWorkflow(
            AgentRuntimeRegistry agentRuntimeRegistry,
            AiProperties aiProperties,
            ChatMemoryStore chatMemoryStore,
            AgentPrompt promptProvider,
            AgentIoLogger ioLogger) {
        this.agentRuntimeRegistry = agentRuntimeRegistry;
        this.aiProperties = aiProperties;
        this.chatMemoryStore = chatMemoryStore;
        this.promptProvider = promptProvider;
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
    }

    public void execute(
            AgentSession session, String agentId, String content, Consumer<SseEvent> emitter) {
        AgentRuntime runtime = agentRuntimeRegistry.require(agentId);
        startStream(session, runtime, session.getSessionId(), content, emitter, 1, "user");
    }

    private void startStream(
            AgentSession session,
            AgentRuntime runtime,
            String sessionId,
            String content,
            Consumer<SseEvent> emitter,
            int attempt,
            String inputSource) {
        ioLogger.logModelInput(sessionId, inputSource, attempt, content);
        AtomicBoolean outputStarted = new AtomicBoolean(false);
        Consumer<SseEvent> loggingEmitter = wrapEmitter(sessionId, emitter);
        TokenStream tokenStream = runtime.chat(
                sessionId,
                content,
                session.getProvider(),
                aiProperties,
                chatMemoryStore,
                promptProvider);
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        tokenStream
                .onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext responseContext) -> {
                    session.attachStreamingHandle(responseContext.streamingHandle());
                    if (session.isTurnCancelled()) {
                        responseContext.streamingHandle().cancel();
                        return;
                    }
                    outputStarted.set(true);
                    String delta = partialResponse.text();
                    if (delta != null && !delta.isEmpty()) {
                        loggingEmitter.accept(SseEvent.token(delta));
                    }
                })
                .onToolExecuted(tool -> {
                    if (session.isTurnCancelled()) {
                        return;
                    }
                    outputStarted.set(true);
                    runtime.onToolExecuted(sessionId, tool, loggingEmitter);
                })
                .onCompleteResponse(response -> handleComplete(
                        session,
                        runtime,
                        sessionId,
                        response,
                        loggingEmitter))
                .onError(error -> {
                    session.clearStreamingHandle();
                    handleStreamError(
                        error,
                        outputStarted.get(),
                        attempt,
                        session,
                        runtime,
                        sessionId,
                        content,
                        inputSource,
                        loggingEmitter);
                })
                .start();
    }

    private void handleStreamError(
            Throwable error,
            boolean outputStarted,
            int attempt,
            AgentSession session,
            AgentRuntime runtime,
            String sessionId,
            String content,
            String inputSource,
            Consumer<SseEvent> emitter) {
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        Throwable root = unwrap(error);
        if (!outputStarted && attempt < MAX_STREAM_ATTEMPTS && isRetryableStreamError(root)) {
            log.warn(
                    "Agent streaming failed (attempt {}/{}), retrying: {}",
                    attempt,
                    MAX_STREAM_ATTEMPTS,
                    root.getMessage());
            String retryContent = buildRetryContent(content, root);
            startStream(session, runtime, sessionId, retryContent, emitter, attempt + 1, inputSource);
            return;
        }
        handleError(root, session, emitter);
    }

    private void handleComplete(
            AgentSession session,
            AgentRuntime runtime,
            String sessionId,
            ChatResponse response,
            Consumer<SseEvent> emitter) {
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        if (response == null) {
            log.warn("Agent completed with empty ChatResponse for session {}", sessionId);
            handleError(new IllegalStateException(EMPTY_RESPONSE_MARKER + ": 模型流式响应为空"), session, emitter);
            return;
        }
        String fullText = response.aiMessage() != null ? response.aiMessage().text() : "";
        ioLogger.logModelOutput(sessionId, response, fullText);
        runtime.onComplete(session, response, emitter);
        session.endTurn();
    }

    private static String buildRetryContent(String originalContent, Throwable error) {
        if (isTruncatedToolArguments(error)) {
            return originalContent + "\n\n" + STREAM_RETRY_HINT;
        }
        return originalContent;
    }


    private Consumer<SseEvent> wrapEmitter(String sessionId, Consumer<SseEvent> delegate) {
        return event -> {
            if (event != null && ioLogger.isEnabled()) {
                ioLogger.logSseToClient(sessionId, event.getEvent(), event.getData());
            }
            delegate.accept(event);
        };
    }

    private void handleError(Throwable error, AgentSession session, Consumer<SseEvent> emitter) {
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        Throwable root = unwrap(error);
        if (isEmptyStreamingResponse(root)) {
            log.warn("Agent streaming returned empty response: {}", root.getMessage());
            emitter.accept(SseEvent.error("EMPTY_RESPONSE", root.getMessage()));
            session.endTurn();
            return;
        }
        if (isContextOverflow(root)) {
            log.warn("Agent context overflow: {}", root.getMessage());
            emitter.accept(SseEvent.error(
                    "CONTEXT_OVERFLOW",
                    "对话上下文过长，请开始新对话或缩短需求后再试"));
            session.endTurn();
            return;
        }
        if (isTruncatedToolArguments(root)) {
            log.warn("Agent streaming produced invalid tool arguments JSON: {}", root.getMessage());
            emitter.accept(SseEvent.error(
                    "INVALID_TOOL_ARGS",
                    TRUNCATED_ARGS_FEEDBACK));
            session.endTurn();
            return;
        }
        log.warn("Agent streaming failed: {}", root.getMessage());
        emitter.accept(SseEvent.error("AGENT_ERROR", formatStreamError(root)));
        session.endTurn();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isRetryableStreamError(Throwable error) {
        if (isEmptyStreamingResponse(error)) {
            return true;
        }
        if (error instanceof java.net.SocketException) {
            return true;
        }
        if (isTruncatedToolArguments(error)) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("broken pipe");
    }

    private static boolean isEmptyStreamingResponse(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        return message != null && message.startsWith(EMPTY_RESPONSE_MARKER);
    }

    private static String formatStreamError(Throwable error) {
        if (isTruncatedToolArguments(error)) {
            return TRUNCATED_ARGS_FEEDBACK;
        }
        if (error instanceof java.net.SocketException) {
            return "网络连接被重置，可能是请求超时或上游 AI 服务断开，请重试";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static boolean isTruncatedToolArguments(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JsonParseException) return true;
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("jsoneofexception") || lower.contains("end-of-input")
                        || lower.contains("expecting closing quote")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isContextOverflow(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("maximum context") || lower.contains("context length")
                        || lower.contains("context window") || lower.contains("token limit")
                        || lower.contains("too many tokens") || lower.contains("reduce the length"))
                    return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
