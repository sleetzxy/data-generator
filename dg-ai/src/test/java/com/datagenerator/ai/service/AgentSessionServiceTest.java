package com.datagenerator.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.config.ChatModelFactory;
import com.datagenerator.ai.dto.CreateSessionRequest;
import com.datagenerator.ai.dto.ProviderInfo;
import com.datagenerator.ai.dto.SkillInfo;
import com.datagenerator.ai.dto.SseEvent;
import com.datagenerator.ai.port.ConnectionCatalogPort;
import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.port.JobExecutionPort;
import com.datagenerator.ai.port.JobPreviewPort;
import com.datagenerator.ai.port.SchemaCatalogPort;
import com.datagenerator.ai.service.exception.AiDisabledException;
import com.datagenerator.ai.session.AgentSession;
import com.datagenerator.ai.session.AgentSessionRegistry;
import com.datagenerator.ai.session.InMemoryChatMemoryStore;
import com.datagenerator.ai.skill.SkillCatalog;
import com.datagenerator.ai.skill.SkillRegistry;
import com.datagenerator.ai.skill.runtime.SkillExecutionContext;
import com.datagenerator.ai.skill.runtime.SkillRuntime;
import com.datagenerator.ai.skill.runtime.SkillRuntimeRegistry;
import com.datagenerator.ai.skill.runtime.generatejob.GenerateJobSkillRuntime;
import com.datagenerator.ai.tool.generatejob.JobGeneratorTools;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSessionServiceTest {

    private AgentSessionService service;
    private SkillRegistry skillRegistry;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);
        aiProperties.setRequestTimeout(Duration.ofSeconds(90));

        skillRegistry = new SkillRegistry();
        skillRegistry.loadFromClasspath();

        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        JobGeneratorTools tools = new JobGeneratorTools(
                mock(ConnectionCatalogPort.class),
                mock(JobDefinitionPort.class),
                mock(SchemaCatalogPort.class),
                mock(JobPreviewPort.class),
                mock(JobExecutionPort.class),
                sessionRegistry);
        GenerateJobSkillRuntime generateJobRuntime =
                new GenerateJobSkillRuntime(tools, mock(JobDefinitionPort.class));
        SkillRuntimeRegistry runtimeRegistry = new SkillRuntimeRegistry(List.of(generateJobRuntime));

        service = new AgentSessionService(
                aiProperties,
                skillRegistry,
                runtimeRegistry,
                new ChatModelFactory(aiProperties),
                new InMemoryChatMemoryStore(aiProperties),
                sessionRegistry);
    }

    @Test
    void createSession_unknownSkill_throws() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setSkillId("unknown-skill");

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown skill");
    }

    @Test
    void createSession_noProviderConfigured_throwsAiDisabled() {
        AiProperties emptyProps = new AiProperties();
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        JobGeneratorTools tools = new JobGeneratorTools(
                mock(ConnectionCatalogPort.class),
                mock(JobDefinitionPort.class),
                mock(SchemaCatalogPort.class),
                mock(JobPreviewPort.class),
                mock(JobExecutionPort.class),
                sessionRegistry);
        GenerateJobSkillRuntime runtime =
                new GenerateJobSkillRuntime(tools, mock(JobDefinitionPort.class));
        AgentSessionService disabledService = new AgentSessionService(
                emptyProps,
                skillRegistry,
                new SkillRuntimeRegistry(List.of(runtime)),
                new ChatModelFactory(emptyProps),
                new InMemoryChatMemoryStore(emptyProps),
                sessionRegistry);

        CreateSessionRequest request = new CreateSessionRequest();
        request.setSkillId("generate-job");

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
    void listSkills_returnsLoadedSkills() {
        List<SkillInfo> skills = service.listSkills();

        assertThat(skills).extracting(SkillInfo::getId).contains("generate-job");
    }

    @Test
    void sendMessage_connectionResetBeforeOutput_retriesOnceAndCompletes() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        SkillRuntime runtime = mock(SkillRuntime.class);
        when(runtime.skillId()).thenReturn("generate-job");
        TokenStream failingStream = mock(TokenStream.class);
        TokenStream succeedingStream = mock(TokenStream.class);
        when(failingStream.onPartialResponse(any())).thenReturn(failingStream);
        when(failingStream.onToolExecuted(any())).thenReturn(failingStream);
        when(failingStream.onCompleteResponse(any())).thenReturn(failingStream);
        when(failingStream.onError(any())).thenReturn(failingStream);
        when(succeedingStream.onPartialResponse(any())).thenReturn(succeedingStream);
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

        AtomicReference<Consumer<String>> successTokenHandler = new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> successCompleteHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            successTokenHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onPartialResponse(any());
        doAnswer(invocation -> {
            successCompleteHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onCompleteResponse(any());
        doAnswer(invocation -> {
            successTokenHandler.get().accept("ok");
            successCompleteHandler.get().accept(mock(ChatResponse.class));
            return null;
        }).when(succeedingStream).start();

        when(runtime.chat(eq("session-1"), eq("hello"), any(SkillExecutionContext.class)))
                .thenReturn(failingStream, succeedingStream);

        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        SkillCatalog skillCatalog = mock(SkillCatalog.class);
        SkillRuntimeRegistry runtimeRegistry = new SkillRuntimeRegistry(List.of(runtime));
        AgentSessionService retryService = new AgentSessionService(
                aiProperties,
                skillCatalog,
                runtimeRegistry,
                new ChatModelFactory(aiProperties),
                new InMemoryChatMemoryStore(aiProperties),
                sessionRegistry);

        sessionRegistry.put(new AgentSession(
                "session-1", "generate-job", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-1", "hello", events::add);

        verify(runtime, times(2)).chat(eq("session-1"), eq("hello"), any(SkillExecutionContext.class));
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "done");
        assertThat(events).noneMatch(event -> "error".equals(event.getEvent()));
    }

    @Test
    void sendMessage_connectionResetAfterOutput_doesNotRetry() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        SkillRuntime runtime = mock(SkillRuntime.class);
        when(runtime.skillId()).thenReturn("generate-job");

        TokenStream failingStream = mock(TokenStream.class);
        when(failingStream.onPartialResponse(any())).thenReturn(failingStream);
        when(failingStream.onToolExecuted(any())).thenReturn(failingStream);
        when(failingStream.onCompleteResponse(any())).thenReturn(failingStream);
        when(failingStream.onError(any())).thenReturn(failingStream);

        AtomicReference<Consumer<String>> tokenHandler = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> errorHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            tokenHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onPartialResponse(any());
        doAnswer(invocation -> {
            errorHandler.set(invocation.getArgument(0));
            return failingStream;
        }).when(failingStream).onError(any());
        doAnswer(invocation -> {
            tokenHandler.get().accept("partial");
            errorHandler.get().accept(new SocketException("Connection reset"));
            return null;
        }).when(failingStream).start();

        when(runtime.chat(eq("session-2"), eq("hello"), any(SkillExecutionContext.class)))
                .thenReturn(failingStream);

        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        SkillCatalog skillCatalog = mock(SkillCatalog.class);
        SkillRuntimeRegistry runtimeRegistry = new SkillRuntimeRegistry(List.of(runtime));
        AgentSessionService retryService = new AgentSessionService(
                aiProperties,
                skillCatalog,
                runtimeRegistry,
                new ChatModelFactory(aiProperties),
                new InMemoryChatMemoryStore(aiProperties),
                sessionRegistry);

        sessionRegistry.put(new AgentSession(
                "session-2", "generate-job", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-2", "hello", events::add);

        verify(runtime, times(1)).chat(eq("session-2"), eq("hello"), any(SkillExecutionContext.class));
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "error");
    }

    @Test
    void sendMessage_truncatedToolArgumentsBeforeOutput_retriesOnce() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        SkillRuntime runtime = mock(SkillRuntime.class);
        when(runtime.skillId()).thenReturn("generate-job");

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

        AtomicReference<Consumer<String>> successTokenHandler = new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> successCompleteHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            successTokenHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onPartialResponse(any());
        doAnswer(invocation -> {
            successCompleteHandler.set(invocation.getArgument(0));
            return succeedingStream;
        }).when(succeedingStream).onCompleteResponse(any());
        doAnswer(invocation -> {
            successTokenHandler.get().accept("ok");
            successCompleteHandler.get().accept(mock(ChatResponse.class));
            return null;
        }).when(succeedingStream).start();

        when(runtime.chat(eq("session-3"), eq("hello"), any(SkillExecutionContext.class)))
                .thenReturn(failingStream, succeedingStream);

        AgentSessionService retryService = buildRetryService(sessionRegistry, runtime);
        sessionRegistry.put(new AgentSession(
                "session-3", "generate-job", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-3", "hello", events::add);

        verify(runtime, times(2)).chat(eq("session-3"), eq("hello"), any(SkillExecutionContext.class));
        assertThat(events).extracting(SseEvent::getEvent).contains("token", "done");
    }

    @Test
    void sendMessage_truncatedToolArgumentsAfterRetries_emitsFriendlyError() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        SkillRuntime runtime = mock(SkillRuntime.class);
        when(runtime.skillId()).thenReturn("generate-job");

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

        when(runtime.chat(eq("session-4"), eq("hello"), any(SkillExecutionContext.class)))
                .thenReturn(failingStream, failingStream);

        AgentSessionService retryService = buildRetryService(sessionRegistry, runtime);
        sessionRegistry.put(new AgentSession(
                "session-4", "generate-job", "ollama-local", Instant.now()));

        List<SseEvent> events = new ArrayList<>();
        retryService.sendMessage("session-4", "hello", events::add);

        assertThat(events).anyMatch(event ->
                "error".equals(event.getEvent()) && event.getData().contains("validateDraftJobYaml"));
    }

    private static void stubChainingStream(TokenStream stream) {
        when(stream.onPartialResponse(any())).thenReturn(stream);
        when(stream.onToolExecuted(any())).thenReturn(stream);
        when(stream.onCompleteResponse(any())).thenReturn(stream);
        when(stream.onError(any())).thenReturn(stream);
    }

    private static AgentSessionService buildRetryService(
            AgentSessionRegistry sessionRegistry, SkillRuntime runtime) {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setDefaultProvider("ollama-local");
        AiProperties.ProviderProperties provider = new AiProperties.ProviderProperties();
        provider.setType("ollama");
        provider.setBaseUrl("http://localhost:11434");
        provider.setModel("test-model");
        aiProperties.getProviders().put("ollama-local", provider);

        SkillCatalog skillCatalog = mock(SkillCatalog.class);
        SkillRuntimeRegistry runtimeRegistry = new SkillRuntimeRegistry(List.of(runtime));
        return new AgentSessionService(
                aiProperties,
                skillCatalog,
                runtimeRegistry,
                new ChatModelFactory(aiProperties),
                new InMemoryChatMemoryStore(aiProperties),
                sessionRegistry);
    }
}
