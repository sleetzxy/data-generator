package com.datagenerator.ai.skill.runtime.generatejob;

import com.datagenerator.ai.agent.JobGeneratorAgent;
import com.datagenerator.ai.service.SseEventFactory;
import com.datagenerator.ai.dto.SseEvent;
import com.datagenerator.ai.artifact.YamlArtifactExtractor;
import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.session.AgentSession;
import com.datagenerator.ai.skill.runtime.SkillExecutionContext;
import com.datagenerator.ai.skill.runtime.SkillRuntime;
import com.datagenerator.ai.tool.generatejob.JobGeneratorTools;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenerateJobSkillRuntime implements SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(GenerateJobSkillRuntime.class);
    public static final String SKILL_ID = "generate-job";

    private final JobGeneratorTools tools;
    private final JobDefinitionPort jobDefinitions;
    private final ConcurrentHashMap<String, JobGeneratorAgent> agents = new ConcurrentHashMap<>();

    public GenerateJobSkillRuntime(JobGeneratorTools tools, JobDefinitionPort jobDefinitions) {
        this.tools = tools;
        this.jobDefinitions = jobDefinitions;
    }

    @Override
    public String skillId() {
        return SKILL_ID;
    }

    @Override
    public TokenStream chat(String sessionId, String userMessage, SkillExecutionContext context) {
        JobGeneratorAgent agent = agents.computeIfAbsent(sessionId, id -> buildAgent(context));
        return agent.chat(sessionId, userMessage);
    }

    @Override
    public void onComplete(AgentSession session, String fullText, Consumer<SseEvent> emitter) {
        YamlArtifactExtractor.extract(fullText).ifPresent(yaml -> {
            JobDefinitionPort.ValidationResult result = jobDefinitions.validateYaml(yaml);
            if (result.valid()) {
                session.setDraftYaml(yaml);
                emitter.accept(SseEventFactory.artifactYaml(yaml));
            } else {
                log.warn("Extracted YAML failed validation for session {}: {}", session.getSessionId(), result.errors());
            }
        });
    }

    @Override
    public void evictSession(String sessionId) {
        agents.remove(sessionId);
    }

    private JobGeneratorAgent buildAgent(SkillExecutionContext context) {
        return AiServices.builder(JobGeneratorAgent.class)
                .streamingChatModel(context.chatModelFactory().getStreamingModel(context.provider()))
                .tools(tools)
                .chatMemoryProvider(id -> context.chatMemoryStore().getOrCreate((String) id))
                .systemMessageProvider(id -> context.skillCatalog().buildSystemPrompt(SKILL_ID))
                .build();
    }
}
