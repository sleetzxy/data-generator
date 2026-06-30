package com.datagenerator.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.agent.runtime.JobGeneratorAgentRuntime;
import com.datagenerator.ai.application.AgentSessionApplicationService.ProviderDescriptor;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.workflow.AgentExecutionWorkflow;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.web.dto.request.CreateSessionRequest;
import com.datagenerator.ai.application.sse.SseEvent;
import com.datagenerator.ai.memory.InMemoryChatMemoryStore;
import com.datagenerator.ai.prompt.AgentPrompt;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_DISABLED");
    }

    @Test
    void listProviders_returnsConfiguredProviders() {
        List<ProviderDescriptor> providers = service.listProviders();

        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).id()).isEqualTo("ollama-local");
    }

    @Test
    void listAgents_returnsConfiguredAgent() {
        assertThat(service.listAgents()).extracting(info -> info.id()).contains("job-generator");
    }

    @Test
    void sendMessage_activeTurn_throwsConflict() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentSessionApplicationService localService = buildRetryService(sessionRegistry, mock(AgentRuntime.class));
        sessionRegistry.put(new AgentSession(
                "session-busy",
                "job-generator",
                "ollama-local",
                Instant.now()));
        AgentSession session = sessionRegistry.find("session-busy").orElseThrow();
        session.tryBeginTurn();

        assertThatThrownBy(() -> localService.sendMessage("session-busy", "hello", event -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SESSION_CONFLICT");

        session.endTurn();
    }

    @Test
    void sendMessage_connectionResetBeforeOutput_retriesOnceAndCompletes() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn("job-generator");
        doAnswer(invocation -> {
            Consumer<SseEvent> emitter = invocation.getArgument(2);
            emitter.accept(SseEvent.done());
            return true;
        }).when(runtime).onComplete(any(), any(), any());
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

        when(runtime.chat(eq("session-1"), eq("hello"), anyString(), any(), any(), any()))
                .thenReturn(failingStream, succeedingStream);

        AiProperties aiProperties = new AiProperties();
        configureJobGeneratorAgent(aiProperties);
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        AgentSessionApplicationService retryService =
                buildService(aiProperties, sessionRegistry, runtime);

        sessionRegistry.put(new AgentSession(
                "session-1", "job-generator", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-1", "hello", events::add);

        verify(runtime, times(2)).chat(eq("session-1"), eq("hello"), anyString(), any(), any(), any());
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

        when(runtime.chat(eq("session-2"), eq("hello"), anyString(), any(), any(), any()))
                .thenReturn(failingStream);

        AiProperties aiProperties = new AiProperties();
        configureJobGeneratorAgent(aiProperties);
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        AgentSessionApplicationService retryService =
                buildService(aiProperties, sessionRegistry, runtime);

        sessionRegistry.put(new AgentSession(
                "session-2", "job-generator", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-2", "hello", events::add);

        verify(runtime, times(1)).chat(eq("session-2"), eq("hello"), anyString(), any(), any(), any());
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "error");
    }

    @Test
    void sendMessage_truncatedToolArgumentsBeforeOutput_retriesOnce() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn("job-generator");
        doAnswer(invocation -> {
            Consumer<SseEvent> emitter = invocation.getArgument(2);
            emitter.accept(SseEvent.done());
            return true;
        }).when(runtime).onComplete(any(), any(), any());

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

        when(runtime.chat(eq("session-3"), org.mockito.ArgumentMatchers.anyString(), anyString(), any(), any(), any()))
                .thenReturn(failingStream, succeedingStream);

        AgentSessionApplicationService retryService = buildRetryService(sessionRegistry, runtime);
        sessionRegistry.put(new AgentSession(
                "session-3", "job-generator", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-3", "hello", events::add);

        verify(runtime, times(2)).chat(
                eq("session-3"),
                org.mockito.ArgumentMatchers.contains("hello"),
                anyString(),
                any(),
                any(),
                any());
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

        when(runtime.chat(
                        org.mockito.ArgumentMatchers.eq("session-4"),
                        org.mockito.ArgumentMatchers.anyString(),
                        anyString(),
                        any(),
                        any(),
                        any()))
                .thenReturn(failingStream, failingStream, failingStream);

        AgentSessionApplicationService retryService = buildRetryService(sessionRegistry, runtime);
        sessionRegistry.put(new AgentSession(
                "session-4", "job-generator", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-4", "hello", events::add);

        verify(runtime, times(3)).chat(
                org.mockito.ArgumentMatchers.eq("session-4"),
                org.mockito.ArgumentMatchers.anyString(),
                anyString(),
                any(),
                any(),
                any());
        assertThat(events).anyMatch(event ->
                "error".equals(event.getEvent()) && event.getData().contains("被截断"));
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
        JobGeneratorAgentRuntime jobRuntime =
                new JobGeneratorAgentRuntime(
                        webClient,
                        AgentIoLogger.disabled(),
                        sessionRegistry);
        AgentRuntime runtime = runtimeOverride != null ? runtimeOverride : jobRuntime;
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(List.of(runtime));
        InMemoryChatMemoryStore chatMemoryStore = new InMemoryChatMemoryStore(aiProperties);
        AgentPrompt agentPrompt = new AgentPrompt();
        AgentExecutionWorkflow executionWorkflow = new AgentExecutionWorkflow(
                runtimeRegistry,
                aiProperties,
                chatMemoryStore,
                agentPrompt,
                AgentIoLogger.disabled());
        return new AgentSessionApplicationService(
                aiProperties,
                runtimeRegistry,
                chatMemoryStore,
                sessionRegistry,
                executionWorkflow);
    }

    private static void configureJobGeneratorAgent(AiProperties aiProperties) {
        AiProperties.AgentProperties agent = new AiProperties.AgentProperties();
        agent.setProvider("ollama-local");
        aiProperties.getAgents().put("job-generator", agent);
    }
}
