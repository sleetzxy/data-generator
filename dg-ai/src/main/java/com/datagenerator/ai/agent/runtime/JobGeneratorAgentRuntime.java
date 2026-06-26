package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.agent.factory.JobGeneratorAgentFactory;
import com.datagenerator.ai.agent.instances.JobGeneratorAgent;
import com.datagenerator.ai.agent.result.DraftResultProcessor;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.registry.ToolRegistry;
import com.datagenerator.ai.web.dto.common.SseEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class JobGeneratorAgentRuntime implements AgentRuntime {

    public static final String AGENT_ID = "job-generator";

    private final JobGeneratorAgentFactory agentFactory;
    private final DraftResultProcessor resultProcessor;
    private final StreamingHandleRegistry streamingHandleRegistry;
    private final ConcurrentHashMap<String, JobGeneratorAgent> agents = new ConcurrentHashMap<>();

    public JobGeneratorAgentRuntime(
            ToolRegistry toolRegistry,
            DataGeneratorWebClient webClient,
            AgentIoLogger ioLogger,
            AiProperties.DraftContinueProperties draftContinueSettings,
            StreamingHandleRegistry streamingHandleRegistry) {
        this.agentFactory = new JobGeneratorAgentFactory(toolRegistry, ioLogger);
        this.resultProcessor = new DraftResultProcessor(webClient, ioLogger, draftContinueSettings);
        this.streamingHandleRegistry = streamingHandleRegistry;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public TokenStream chat(String sessionId, String userMessage, AgentExecutionContext context) {
        JobGeneratorAgent agent =
                agents.computeIfAbsent(sessionId, id -> agentFactory.create(context));
        return agent.chat(sessionId, userMessage);
    }

    @Override
    public boolean onComplete(AgentSession session, ChatResponse response, Consumer<SseEvent> emitter) {
        streamingHandleRegistry.unregister(session.getSessionId());
        return resultProcessor.process(session, response, emitter);
    }

    @Override
    public void evictSession(String sessionId) {
        agents.remove(sessionId);
        streamingHandleRegistry.cancel(sessionId);
    }
}
