package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.agent.factory.JobGeneratorAgentFactory;
import com.datagenerator.ai.agent.instances.JobGeneratorAgent;
import com.datagenerator.ai.agent.result.JobResultProcessor;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.sse.SseEvent;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.prompt.AgentPrompt;
import com.datagenerator.ai.tool.JobGeneratorTools;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
import com.fasterxml.jackson.core.JsonParseException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JobGeneratorAgentRuntime implements AgentRuntime {

    public static final String AGENT_ID = "job-generator";

    private static final Logger log = LoggerFactory.getLogger(JobGeneratorAgentRuntime.class);

    private final JobGeneratorTools tools;
    private final JobGeneratorAgentFactory agentFactory;
    private final JobResultProcessor resultProcessor;
    private final AgentSessionRegistry sessionRegistry;
    private final AgentIoLogger ioLogger;
    private final ConcurrentHashMap<String, JobGeneratorAgent> agents = new ConcurrentHashMap<>();

    public JobGeneratorAgentRuntime(
            DataGeneratorWebClient webClient,
            AgentIoLogger ioLogger,
            AgentSessionRegistry sessionRegistry) {
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
        this.resultProcessor = new JobResultProcessor(webClient);
        this.tools = new JobGeneratorTools(webClient, sessionRegistry, this.resultProcessor);
        this.agentFactory = new JobGeneratorAgentFactory(this.ioLogger);
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Map<ToolSpecification, ToolExecutor> createToolExecutors(AgentIoLogger ioLogger) {
        AgentIoLogger logger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (Method method : tools.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                DefaultToolExecutor delegate = new DefaultToolExecutor(tools, method);
                executors.put(spec, (request, memoryId) -> {
                    String sessionId = memoryId != null ? String.valueOf(memoryId) : "unknown";
                    String toolName = request != null ? request.name() : "unknown";
                    try {
                        String result = delegate.execute(request, memoryId);
                        if (result == null || result.isBlank()) {
                            result = "[" + toolName + " 未返回有效结果；请重试或检查 dg-web 连接]";
                        }
                        logger.logToolCall(sessionId, toolName,
                                request != null ? request.arguments() : null, result);
                        return result;
                    } catch (RuntimeException e) {
                        if (isJsonTruncation(e)) {
                            return "工具参数 JSON 不完整，请缩短参数后重试";
                        }
                        throw e;
                    }
                });
            }
        }
        return executors;
    }

    private static boolean isJsonTruncation(Throwable error) {
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

    @Override
    public TokenStream chat(
            String sessionId,
            String userMessage,
            String provider,
            AiProperties aiProperties,
            ChatMemoryStore chatMemoryStore,
            AgentPrompt promptProvider) {
        JobGeneratorAgent agent =
                agents.computeIfAbsent(
                        sessionId,
                        id -> agentFactory.create(
                                AGENT_ID,
                                provider,
                                aiProperties,
                                chatMemoryStore,
                                promptProvider,
                                createToolExecutors(ioLogger)));
        return agent.chat(sessionId, userMessage);
    }

    @Override
    public boolean onComplete(AgentSession session, ChatResponse response, Consumer<SseEvent> emitter) {
        resultProcessor.process(session, response, emitter);
        String messageId = java.util.UUID.randomUUID().toString();
        String draftYaml = JobSessionState.draftYaml(session);
        boolean hasDraft = draftYaml != null && !draftYaml.isBlank();
        log.info("Stream done for session {}: draftIncomplete={}, draftValidated={}, hasDraft={}, chars={}",
                session.getSessionId(),
                JobSessionState.isDraftIncomplete(session),
                JobSessionState.isDraftValidated(session),
                hasDraft,
                draftYaml != null ? draftYaml.length() : 0);
        emitter.accept(SseEvent.done(Map.of(
                "messageId", messageId,
                "draftIncomplete", JobSessionState.isDraftIncomplete(session),
                "draftValidated", JobSessionState.isDraftValidated(session),
                "hasDraft", hasDraft)));
        JobSessionState.clearDraftPersistedInTurn(session);
        return !JobSessionState.isDraftIncomplete(session);
    }

    @Override
    public void onToolExecuted(String sessionId, ToolExecution tool, Consumer<SseEvent> emitter) {
        String name = tool.request() != null ? tool.request().name() : "unknown";
        if (isJobPersistTool(name)) {
            sessionRegistry.find(sessionId).ifPresent(session -> {
                JobSessionState.markDraftPersistedInTurn(session);
                emitter.accept(SseEvent.event("job_saved", Map.of("status", "ok")));
            });
        }
        emitter.accept(SseEvent.tool(name, "done"));
    }

    private static boolean isJobPersistTool(String toolName) {
        return "saveDraftJobDefinition".equals(toolName)
                || "updateDraftJobDefinition".equals(toolName);
    }

    @Override
    public void evictSession(String sessionId) {
        agents.remove(sessionId);
    }
}
