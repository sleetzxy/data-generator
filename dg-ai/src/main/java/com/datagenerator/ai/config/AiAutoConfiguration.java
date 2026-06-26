package com.datagenerator.ai.config;

import com.datagenerator.ai.agent.orchestrator.AgentOrchestrator;
import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.agent.runtime.JobGeneratorAgentRuntime;
import com.datagenerator.ai.agent.runtime.JobGeneratorMemoryCompressor;
import com.datagenerator.ai.agent.runtime.StreamingHandleRegistry;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.workflow.AgentConversationWorkflow;
import com.datagenerator.ai.application.workflow.AgentExecutionWorkflow;
import com.datagenerator.ai.application.workflow.AgentRoutingWorkflow;
import com.datagenerator.ai.memory.ChatMemoryContentCompressor;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.memory.InMemoryChatMemoryStore;
import com.datagenerator.ai.model.adapter.ChatModelFactory;
import com.datagenerator.ai.prompt.provider.PromptProvider;
import com.datagenerator.ai.prompt.provider.TemplatePromptProvider;
import com.datagenerator.ai.prompt.templates.PromptTemplateLoader;
import com.datagenerator.ai.tool.definition.ToolProvider;
import com.datagenerator.ai.tool.impl.JobGeneratorTools;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import com.datagenerator.ai.tool.registry.ToolRegistry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(DataGeneratorWebConfiguration.class)
@ConditionalOnProperty(prefix = "ai", name = {"server", "enabled"}, havingValue = "true")
public class AiAutoConfiguration {

    @Bean
    AgentSessionRegistry agentSessionRegistry() {
        return new AgentSessionRegistry();
    }

    @Bean
    PromptTemplateLoader promptTemplateLoader() {
        return new PromptTemplateLoader();
    }

    @Bean
    PromptProvider promptProvider(PromptTemplateLoader promptTemplateLoader) {
        return new TemplatePromptProvider(promptTemplateLoader);
    }

    @Bean
    ChatModelFactory chatModelFactory(AiProperties properties) {
        return new ChatModelFactory(properties);
    }

    @Bean
    JobGeneratorMemoryCompressor jobGeneratorMemoryCompressor() {
        return new JobGeneratorMemoryCompressor();
    }

    @Bean
    JobGeneratorTools jobGeneratorTools(
            DataGeneratorWebClient dataGeneratorWebClient,
            AgentSessionRegistry sessionRegistry,
            JobGeneratorMemoryCompressor compressor) {
        return new JobGeneratorTools(dataGeneratorWebClient, sessionRegistry, compressor);
    }

    @Bean
    JobGeneratorToolProvider jobGeneratorToolProvider(JobGeneratorTools jobGeneratorTools) {
        return new JobGeneratorToolProvider(jobGeneratorTools);
    }

    @Bean
    ToolRegistry toolRegistry(List<ToolProvider> toolProviders) {
        return new ToolRegistry(toolProviders);
    }

    @Bean
    AgentIoLogger agentIoLogger(AiProperties properties) {
        return new AgentIoLogger(properties);
    }

    @Bean
    StreamingHandleRegistry streamingHandleRegistry() {
        return new StreamingHandleRegistry();
    }

    @Bean
    JobGeneratorAgentRuntime jobGeneratorAgentRuntime(
            ToolRegistry toolRegistry,
            DataGeneratorWebClient dataGeneratorWebClient,
            AgentIoLogger agentIoLogger,
            AiProperties properties,
            StreamingHandleRegistry streamingHandleRegistry) {
        return new JobGeneratorAgentRuntime(
                toolRegistry, dataGeneratorWebClient, agentIoLogger, properties.getDraftContinue(),
                streamingHandleRegistry);
    }

    @Bean
    AgentRuntimeRegistry agentRuntimeRegistry(List<AgentRuntime> runtimes) {
        return new AgentRuntimeRegistry(runtimes);
    }

    @Bean
    ChatMemoryStore chatMemoryStore(AiProperties properties, ChatMemoryContentCompressor compressor) {
        return new InMemoryChatMemoryStore(properties, compressor);
    }

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor(AiProperties properties) {
        int size = Math.max(1, properties.getAgentThreadPoolSize());
        return Executors.newFixedThreadPool(size);
    }

    @Bean
    AgentOrchestrator agentOrchestrator(
            AgentRuntimeRegistry agentRuntimeRegistry,
            ToolRegistry toolRegistry,
            ChatModelFactory chatModelFactory,
            ChatMemoryStore chatMemoryStore,
            PromptProvider promptProvider,
            AiProperties aiProperties) {
        return new AgentOrchestrator(
                agentRuntimeRegistry,
                toolRegistry,
                chatModelFactory,
                chatMemoryStore,
                promptProvider,
                aiProperties);
    }

    @Bean
    AgentRoutingWorkflow agentRoutingWorkflow(AgentRuntimeRegistry agentRuntimeRegistry) {
        return new AgentRoutingWorkflow(agentRuntimeRegistry);
    }

    @Bean
    AgentExecutionWorkflow agentExecutionWorkflow(
            AgentOrchestrator agentOrchestrator,
            ChatMemoryStore chatMemoryStore,
            AgentIoLogger agentIoLogger,
            PromptTemplateLoader promptTemplateLoader,
            StreamingHandleRegistry streamingHandleRegistry) {
        return new AgentExecutionWorkflow(
                agentOrchestrator, chatMemoryStore, agentIoLogger, promptTemplateLoader, streamingHandleRegistry);
    }

    @Bean
    AgentConversationWorkflow agentConversationWorkflow(
            AgentRoutingWorkflow agentRoutingWorkflow, AgentExecutionWorkflow agentExecutionWorkflow) {
        return new AgentConversationWorkflow(agentRoutingWorkflow, agentExecutionWorkflow);
    }

    @Bean
    AgentSessionApplicationService agentSessionApplicationService(
            AiProperties properties,
            AgentOrchestrator orchestrator,
            ChatModelFactory chatModelFactory,
            ChatMemoryStore chatMemoryStore,
            AgentSessionRegistry sessionRegistry,
            AgentRoutingWorkflow agentRoutingWorkflow,
            AgentConversationWorkflow agentConversationWorkflow) {
        return new AgentSessionApplicationService(
                properties,
                orchestrator,
                chatModelFactory,
                chatMemoryStore,
                sessionRegistry,
                agentRoutingWorkflow,
                agentConversationWorkflow);
    }
}
