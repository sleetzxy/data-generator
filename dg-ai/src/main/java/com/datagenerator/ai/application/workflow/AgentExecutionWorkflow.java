package com.datagenerator.ai.application.workflow;

import com.datagenerator.ai.exception.EmptyStreamingChatResponseException;
import com.datagenerator.ai.agent.orchestrator.AgentOrchestrator;
import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.SseEventFactory;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.TurnContinueMode;
import com.datagenerator.ai.web.dto.common.SseEvent;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.prompt.templates.PromptTemplateLoader;
import com.datagenerator.ai.agent.runtime.AgentExecutionContext;
import com.datagenerator.ai.agent.runtime.StreamingHandleRegistry;
import com.datagenerator.ai.tool.interceptor.ToolArgumentErrors;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.service.TokenStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 执行已路由 Agent 的流式对话与结构化输出续写。 */
public class AgentExecutionWorkflow {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionWorkflow.class);
    private static final int MAX_STREAM_ATTEMPTS = 3;

    private final AgentOrchestrator orchestrator;
    private final ChatMemoryStore chatMemoryStore;
    private final AgentIoLogger ioLogger;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamingHandleRegistry streamingHandleRegistry;

    public AgentExecutionWorkflow(
            AgentOrchestrator orchestrator,
            ChatMemoryStore chatMemoryStore,
            AgentIoLogger ioLogger,
            PromptTemplateLoader promptTemplateLoader,
            StreamingHandleRegistry streamingHandleRegistry) {
        this.orchestrator = orchestrator;
        this.chatMemoryStore = chatMemoryStore;
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
        this.promptTemplateLoader = promptTemplateLoader;
        this.streamingHandleRegistry = streamingHandleRegistry;
    }

    public void execute(
            AgentSession session, String agentId, String content, Consumer<SseEvent> emitter) {
        AgentRuntime runtime = orchestrator.requireRuntime(agentId);
        AgentExecutionContext context = orchestrator.createContext(session);
        startStream(session, runtime, context, session.getSessionId(), content, emitter, 1, "user");
    }

    private void startStream(
            AgentSession session,
            AgentRuntime runtime,
            AgentExecutionContext context,
            String sessionId,
            String content,
            Consumer<SseEvent> emitter,
            int attempt,
            String inputSource) {
        ioLogger.logModelInput(sessionId, inputSource, attempt, content);
        AtomicBoolean outputStarted = new AtomicBoolean(false);
        StructuredOutputMessageFilter messageFilter = new StructuredOutputMessageFilter();
        Consumer<SseEvent> loggingEmitter = wrapEmitter(sessionId, emitter);
        TokenStream tokenStream = runtime.chat(sessionId, content, context);
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        tokenStream
                .onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext responseContext) -> {
                    streamingHandleRegistry.register(sessionId, responseContext.streamingHandle());
                    if (session.isTurnCancelled()) {
                        responseContext.streamingHandle().cancel();
                        return;
                    }
                    outputStarted.set(true);
                    String visible = messageFilter.accept(partialResponse.text());
                    if (visible != null && !visible.isEmpty()) {
                        loggingEmitter.accept(SseEventFactory.token(visible));
                    }
                })
                .onToolExecuted(tool -> {
                    if (session.isTurnCancelled()) {
                        return;
                    }
                    outputStarted.set(true);
                    String toolName = tool.request() != null ? tool.request().name() : "unknown";
                    if (isJobPersistTool(toolName)) {
                        session.markDraftPersistedInTurn();
                        loggingEmitter.accept(SseEventFactory.jobSaved());
                    }
                    loggingEmitter.accept(SseEventFactory.tool(toolName, "done"));
                })
                .onCompleteResponse(response -> handleComplete(
                        session,
                        runtime,
                        context,
                        sessionId,
                        response,
                        messageFilter,
                        loggingEmitter))
                .onError(error -> {
                    streamingHandleRegistry.unregister(sessionId);
                    handleStreamError(
                        error,
                        outputStarted.get(),
                        attempt,
                        session,
                        runtime,
                        context,
                        sessionId,
                        content,
                        inputSource,
                        messageFilter,
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
            AgentExecutionContext context,
            String sessionId,
            String content,
            String inputSource,
            StructuredOutputMessageFilter messageFilter,
            Consumer<SseEvent> emitter) {
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        emitFilteredTrailing(messageFilter, emitter);
        Throwable root = unwrap(error);
        if (!outputStarted && attempt < MAX_STREAM_ATTEMPTS && isRetryableStreamError(root)) {
            log.warn(
                    "Agent streaming failed (attempt {}/{}), retrying: {}",
                    attempt,
                    MAX_STREAM_ATTEMPTS,
                    root.getMessage());
            String retryContent = buildRetryContent(content, root);
            startStream(session, runtime, context, sessionId, retryContent, emitter, attempt + 1, inputSource);
            return;
        }
        handleError(root, session, emitter);
    }

    private void handleComplete(
            AgentSession session,
            AgentRuntime runtime,
            AgentExecutionContext context,
            String sessionId,
            ChatResponse response,
            StructuredOutputMessageFilter messageFilter,
            Consumer<SseEvent> emitter) {
        if (session.isTurnCancelled()) {
            session.endTurn();
            return;
        }
        if (response == null) {
            log.warn("Agent completed with empty ChatResponse for session {}", sessionId);
            handleError(new EmptyStreamingChatResponseException(), session, emitter);
            return;
        }
        emitFilteredTrailing(
                messageFilter,
                response.aiMessage() != null ? response.aiMessage().text() : "",
                emitter);
        String fullText = response.aiMessage() != null ? response.aiMessage().text() : "";
        ioLogger.logModelOutput(sessionId, response, fullText);
        boolean needsContinue = runtime.onComplete(session, response, emitter);
        chatMemoryStore.compact(sessionId);
        if (needsContinue) {
            if (session.isTurnCancelled()) {
                session.endTurn();
                return;
            }
            session.incrementTurnContinueAttempts();
            String prompt = resolveContinuePrompt(session);
            String inputSource = "continue-" + session.getContinueMode().name().toLowerCase();
            log.info(
                    "Auto-continuing structured output for session {} (attempt {}, mode={})",
                    sessionId,
                    session.getTurnContinueAttempts(),
                    session.getContinueMode());
            startStream(session, runtime, context, sessionId, prompt, emitter, 1, inputSource);
            return;
        }
        if (session.isDraftIncomplete() && !session.isDraftStopNotified()) {
            log.info("Session {} ended with incomplete draft", sessionId);
        }
        session.setDraftStopNotified(false);
        session.clearDraftPersistedInTurn();
        session.resetTurnContinueAttempts();
        String messageId = UUID.randomUUID().toString();
        boolean hasDraft = session.getDraftYaml() != null && !session.getDraftYaml().isBlank();
        ioLogger.logStreamDone(
                sessionId,
                session.isDraftIncomplete(),
                session.isDraftValidated(),
                hasDraft,
                session.getDraftYaml());
        emitter.accept(SseEventFactory.done(
                messageId, session.isDraftIncomplete(), session.isDraftValidated(), hasDraft));
        session.endTurn();
    }

    private String resolveContinuePrompt(AgentSession session) {
        String agentId = session.getAgentId();
        if (session.getContinueMode() == TurnContinueMode.REPAIR) {
            return buildRepairPrompt(agentId, session.getDraftYaml());
        }
        return promptTemplateLoader.loadFragment(agentId, "continue-append.md");
    }

    private String buildRepairPrompt(String agentId, String draftYaml) {
        String prefix = promptTemplateLoader.loadFragment(agentId, "continue-repair.md");
        String tail = tailLines(draftYaml, 60);
        return prefix + tail + "\n```";
    }

    private static String buildRetryContent(String originalContent, Throwable error) {
        if (ToolArgumentErrors.isTruncatedArguments(error)) {
            return originalContent + "\n\n" + ToolArgumentErrors.STREAM_RETRY_HINT;
        }
        return originalContent;
    }

    private static boolean isJobPersistTool(String toolName) {
        return "saveDraftJobDefinition".equals(toolName) || "createJobDefinition".equals(toolName);
    }

    private static void emitFilteredTrailing(
            StructuredOutputMessageFilter messageFilter, Consumer<SseEvent> emitter) {
        String trailing = messageFilter.flush();
        if (trailing != null && !trailing.isEmpty()) {
            emitter.accept(SseEventFactory.token(trailing));
        }
    }

    private static void emitFilteredTrailing(
            StructuredOutputMessageFilter messageFilter, String fullText, Consumer<SseEvent> emitter) {
        String trailing = messageFilter.reconcileFullText(fullText);
        if (trailing != null && !trailing.isEmpty()) {
            emitter.accept(SseEventFactory.token(trailing));
        }
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
        if (root instanceof EmptyStreamingChatResponseException) {
            log.warn("Agent streaming returned empty response: {}", root.getMessage());
            emitter.accept(SseEventFactory.error("EMPTY_RESPONSE", root.getMessage()));
            session.endTurn();
            return;
        }
        if (ToolArgumentErrors.isContextOverflow(root)) {
            log.warn("Agent context overflow: {}", root.getMessage());
            emitter.accept(SseEventFactory.error(
                    "CONTEXT_OVERFLOW",
                    "对话上下文过长，请开始新对话或缩短需求后再试"));
            session.endTurn();
            return;
        }
        if (ToolArgumentErrors.isTruncatedArguments(root)) {
            log.warn("Agent streaming produced invalid tool arguments JSON: {}", root.getMessage());
            emitter.accept(SseEventFactory.error(
                    "INVALID_TOOL_ARGS",
                    ToolArgumentErrors.TRUNCATED_ARGS_FEEDBACK));
            session.endTurn();
            return;
        }
        log.warn("Agent streaming failed: {}", root.getMessage());
        emitter.accept(SseEventFactory.error("AGENT_ERROR", formatStreamError(root)));
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
        if (error instanceof EmptyStreamingChatResponseException) {
            return true;
        }
        if (error instanceof java.net.SocketException) {
            return true;
        }
        if (ToolArgumentErrors.isTruncatedArguments(error)) {
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

    private static String formatStreamError(Throwable error) {
        if (ToolArgumentErrors.isTruncatedArguments(error)) {
            return ToolArgumentErrors.TRUNCATED_ARGS_FEEDBACK;
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

    private static String tailLines(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length <= maxLines) {
            return text.trim();
        }
        StringBuilder builder = new StringBuilder();
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }
}
