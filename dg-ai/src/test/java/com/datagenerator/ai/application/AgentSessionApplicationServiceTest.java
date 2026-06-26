package com.datagenerator.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.agent.orchestrator.AgentOrchestrator;
import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.agent.runtime.JobGeneratorAgentRuntime;
import com.datagenerator.ai.agent.runtime.StreamingHandleRegistry;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.workflow.AgentConversationWorkflow;
import com.datagenerator.ai.application.workflow.AgentExecutionWorkflow;
import com.datagenerator.ai.application.workflow.AgentRoutingWorkflow;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.web.dto.request.CreateSessionRequest;
import com.datagenerator.ai.web.dto.response.ProviderInfo;
import com.datagenerator.ai.web.dto.common.SseEvent;
import com.datagenerator.ai.memory.InMemoryChatMemoryStore;
import com.datagenerator.ai.model.adapter.ChatModelFactory;
import com.datagenerator.ai.prompt.provider.TemplatePromptProvider;
import com.datagenerator.ai.prompt.templates.PromptTemplateLoader;
import com.datagenerator.ai.exception.AiDisabledException;
import com.datagenerator.ai.exception.SessionConflictException;
import com.datagenerator.ai.agent.runtime.AgentExecutionContext;
import com.datagenerator.ai.tool.impl.JobGeneratorTools;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import com.datagenerator.ai.tool.registry.ToolRegistry;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import java.util.function.BiConsumer;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSessionApplicationServiceTest {

    private AgentSessionApplicationService service;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        configureJobGeneratorAgent(aiProperties);
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);
        aiProperties.setRequestTimeout(Duration.ofSeconds(90));

        service = buildService(aiProperties, new AgentSessionRegistry(), null);
    }

    @Test
    void createSession_unknownAgent_throws() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setAgentId("unknown-agent");

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown agent");
    }

    @Test
    void createSession_missingAgentId_throws() {
        CreateSessionRequest request = new CreateSessionRequest();

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId is required");
    }

    @Test
    void createSession_withAgentId_createsSession() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setAgentId("job-generator");

        var snapshot = service.createSession(request);
        assertThat(snapshot.agentId()).isEqualTo("job-generator");
    }

    @Test
    void createSession_noProviderConfigured_throwsAiDisabled() {
        AiProperties emptyProps = new AiProperties();
        configureJobGeneratorAgent(emptyProps);
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentSessionApplicationService disabledService =
                buildService(emptyProps, sessionRegistry, null);

        CreateSessionRequest request = new CreateSessionRequest();
        request.setAgentId("job-generator");

        assertThatThrownBy(() -> disabledService.createSession(request))
                .isInstanceOf(AiDisabledException.class);
    }

    @Test
    void listProviders_returnsConfiguredProviders() {
        List<ProviderInfo> providers = service.listProviders();

        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getId()).isEqualTo("ollama-local");
    }

    @Test
    void listAgents_returnsConfiguredAgent() {
        assertThat(service.listAgents()).extracting(info -> info.getId()).contains("job-generator");
    }

    @Test
    void sendMessage_activeTurn_throwsConflict() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentSessionApplicationService localService = buildRetryService(sessionRegistry, mock(AgentRuntime.class));
        sessionRegistry.put(new AgentSession(
                "session-busy",
                "job-generator",
                JobGeneratorToolProvider.TOOL_SET_ID,
                "ollama-local",
                Instant.now()));
        AgentSession session = sessionRegistry.find("session-busy").orElseThrow();
        session.tryBeginTurn();

        assertThatThrownBy(() -> localService.sendMessage("session-busy", "hello", event -> {}))
                .isInstanceOf(SessionConflictException.class);

        session.endTurn();
    }

    @Test
    void sendMessage_connectionResetBeforeOutput_retriesOnceAndCompletes() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn("job-generator");
        TokenStream failingStream = mock(TokenStream.class);
        TokenStream succeedingStream = mock(TokenStream.class);
        when(failingStream.onPartialResponseWithContext(any())).thenReturn(failingStream);
        when(failingStream.onToolExecuted(any())).thenReturn(failingStream);
        when(failingStream.onCompleteResponse(any())).thenReturn(failingStream);
        when(failingStream.onError(any())).thenReturn(failingStream);
        when(succeedingStream.onPartialResponseWithContext(any())).thenReturn(succeedingStream);
        when(succeedingStream.onToolExecuted(any())).thenReturn(succeedingStream);
        when(succeedingStream.onCompleteResponse(any())).thenReturn(succeedingStream);
        when(succeedingStream.onError(any())).thenReturn(succeedingStream);

        AtomicReference<Consumer<Throwable>> failingErrorHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            failingErrorHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onError(any());
        doAnswer(invocation -> {
            failingErrorHandler.get().accept(new SocketException("Connection reset"));
            return null;
        }).when(failingStream).start();

        AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> successTokenHandler =
                new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> successCompleteHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            successTokenHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onPartialResponseWithContext(any());
        doAnswer(invocation -> {
            successCompleteHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onCompleteResponse(any());
        doAnswer(invocation -> {
            successTokenHandler
                    .get()
                    .accept(new PartialResponse("ok"), new PartialResponseContext(mock(StreamingHandle.class)));
            successCompleteHandler.get().accept(mock(ChatResponse.class));
            return null;
        }).when(succeedingStream).start();

        when(runtime.chat(eq("session-1"), eq("hello"), any(AgentExecutionContext.class)))
                .thenReturn(failingStream, succeedingStream);

        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        configureJobGeneratorAgent(aiProperties);
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        AgentSessionApplicationService retryService =
                buildService(aiProperties, sessionRegistry, runtime);

        sessionRegistry.put(new AgentSession(
                "session-1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-1", "hello", events::add);

        verify(runtime, times(2)).chat(eq("session-1"), eq("hello"), any(AgentExecutionContext.class));
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "done");
        assertThat(events).noneMatch(event -> "error".equals(event.getEvent()));
    }

    @Test
    void sendMessage_connectionResetAfterOutput_doesNotRetry() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn("job-generator");

        TokenStream failingStream = mock(TokenStream.class);
        when(failingStream.onPartialResponseWithContext(any())).thenReturn(failingStream);
        when(failingStream.onToolExecuted(any())).thenReturn(failingStream);
        when(failingStream.onCompleteResponse(any())).thenReturn(failingStream);
        when(failingStream.onError(any())).thenReturn(failingStream);

        AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> tokenHandler = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> errorHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            tokenHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onPartialResponseWithContext(any());
        doAnswer(invocation -> {
            errorHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onError(any());
        doAnswer(invocation -> {
            tokenHandler
                    .get()
                    .accept(new PartialResponse("partial"), new PartialResponseContext(mock(StreamingHandle.class)));
            errorHandler.get().accept(new SocketException("Connection reset"));
            return null;
        }).when(failingStream).start();

        when(runtime.chat(eq("session-2"), eq("hello"), any(AgentExecutionContext.class)))
                .thenReturn(failingStream);

        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        configureJobGeneratorAgent(aiProperties);
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        AgentSessionApplicationService retryService =
                buildService(aiProperties, sessionRegistry, runtime);

        sessionRegistry.put(new AgentSession(
                "session-2", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-2", "hello", events::add);

        verify(runtime, times(1)).chat(eq("session-2"), eq("hello"), any(AgentExecutionContext.class));
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "error");
    }

    @Test
    void sendMessage_truncatedToolArgumentsBeforeOutput_retriesOnce() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn("job-generator");

        RuntimeException truncatedError = new RuntimeException(
                "com.fasterxml.jackson.core.io.JsonEOFException: Unexpected end-of-input");

        TokenStream failingStream = mock(TokenStream.class);
        TokenStream succeedingStream = mock(TokenStream.class);
        stubChainingStream(failingStream);
        stubChainingStream(succeedingStream);

        AtomicReference<Consumer<Throwable>> failingErrorHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            failingErrorHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onError(any());
        doAnswer(invocation -> {
            failingErrorHandler.get().accept(truncatedError);
            return null;
        }).when(failingStream).start();

        AtomicReference<BiConsumer<PartialResponse, PartialResponseContext>> successTokenHandler =
                new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> successCompleteHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            successTokenHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onPartialResponseWithContext(any());
        doAnswer(invocation -> {
            successCompleteHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onCompleteResponse(any());
        doAnswer(invocation -> {
            successTokenHandler
                    .get()
                    .accept(new PartialResponse("ok"), new PartialResponseContext(mock(StreamingHandle.class)));
            successCompleteHandler.get().accept(mock(ChatResponse.class));
            return null;
        }).when(succeedingStream).start();

        when(runtime.chat(eq("session-3"), org.mockito.ArgumentMatchers.anyString(), any(AgentExecutionContext.class)))
                .thenReturn(failingStream, succeedingStream);

        AgentSessionApplicationService retryService = buildRetryService(sessionRegistry, runtime);
        sessionRegistry.put(new AgentSession(
                "session-3", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-3", "hello", events::add);

        verify(runtime, times(2)).chat(eq("session-3"), org.mockito.ArgumentMatchers.contains("hello"), any(AgentExecutionContext.class));
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "done");
    }

    @Test
    void sendMessage_truncatedToolArgumentsAfterRetries_emitsFriendlyError() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn("job-generator");

        RuntimeException truncatedError = new RuntimeException(
                "com.fasterxml.jackson.core.io.JsonEOFException: Unexpected end-of-input");

        TokenStream failingStream = mock(TokenStream.class);
        stubChainingStream(failingStream);

        AtomicReference<Consumer<Throwable>> errorHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            errorHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onError(any());
        doAnswer(invocation -> {
            errorHandler.get().accept(truncatedError);
            return null;
        }).when(failingStream).start();

        when(runtime.chat(org.mockito.ArgumentMatchers.eq("session-4"), org.mockito.ArgumentMatchers.anyString(), any(AgentExecutionContext.class)))
                .thenReturn(failingStream, failingStream, failingStream);

        AgentSessionApplicationService retryService = buildRetryService(sessionRegistry, runtime);
        sessionRegistry.put(new AgentSession(
                "session-4", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-4", "hello", events::add);

        verify(runtime, times(3)).chat(
                org.mockito.ArgumentMatchers.eq("session-4"),
                org.mockito.ArgumentMatchers.anyString(),
                any(AgentExecutionContext.class));
        assertThat(events).anyMatch(event ->
                "error".equals(event.getEvent()) && event.getData().contains("validateDraftJobYaml"));
    }

    private static void stubChainingStream(TokenStream stream) {
        when(stream.onPartialResponseWithContext(any())).thenReturn(stream);
        when(stream.onToolExecuted(any())).thenReturn(stream);
        when(stream.onCompleteResponse(any())).thenReturn(stream);
        when(stream.onError(any())).thenReturn(stream);
    }

    private static AgentSessionApplicationService buildRetryService(
            AgentSessionRegistry sessionRegistry, AgentRuntime runtime) {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        configureJobGeneratorAgent(aiProperties);
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        return buildService(aiProperties, sessionRegistry, runtime);
    }

    private static AgentSessionApplicationService buildService(
            AiProperties aiProperties,
            AgentSessionRegistry sessionRegistry,
            AgentRuntime runtimeOverride) {
        DataGeneratorWebClient webClient = mock(DataGeneratorWebClient.class);
        JobGeneratorToolProvider toolProvider = new JobGeneratorToolProvider(
                new JobGeneratorTools(webClient, sessionRegistry));
        ToolRegistry toolRegistry = new ToolRegistry(List.of(toolProvider));
        StreamingHandleRegistry streamingHandleRegistry = new StreamingHandleRegistry();
        JobGeneratorAgentRuntime jobRuntime =
                new JobGeneratorAgentRuntime(
                        toolRegistry,
                        webClient,
                        AgentIoLogger.disabled(),
                        aiProperties.getDraftContinue(),
                        streamingHandleRegistry);
        AgentRuntime runtime = runtimeOverride != null ? runtimeOverride : jobRuntime;
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(List.of(runtime));
        ChatModelFactory chatModelFactory = new ChatModelFactory(aiProperties);
        InMemoryChatMemoryStore chatMemoryStore = new InMemoryChatMemoryStore(aiProperties);
        PromptTemplateLoader templateLoader = new PromptTemplateLoader();
        TemplatePromptProvider promptProvider = new TemplatePromptProvider(templateLoader);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                runtimeRegistry,
                toolRegistry,
                chatModelFactory,
                chatMemoryStore,
                promptProvider,
                aiProperties);
        AgentExecutionWorkflow executionWorkflow = new AgentExecutionWorkflow(
                orchestrator, chatMemoryStore, AgentIoLogger.disabled(), templateLoader, streamingHandleRegistry);
        AgentRoutingWorkflow routingWorkflow =
                new AgentRoutingWorkflow(runtimeRegistry);
        AgentConversationWorkflow conversationWorkflow =
                new AgentConversationWorkflow(routingWorkflow, executionWorkflow);
        return new AgentSessionApplicationService(
                aiProperties,
                orchestrator,
                chatModelFactory,
                chatMemoryStore,
                sessionRegistry,
                routingWorkflow,
                conversationWorkflow);
    }

    private static void configureJobGeneratorAgent(AiProperties aiProperties) {
        AiProperties.AgentProperties agent = new AiProperties.AgentProperties();
        agent.setToolSetId(JobGeneratorToolProvider.TOOL_SET_ID);
        aiProperties.getAgents().put("job-generator", agent);
    }
}
