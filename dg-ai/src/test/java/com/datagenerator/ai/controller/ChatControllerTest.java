package com.datagenerator.ai.controller;

import com.datagenerator.ai.dto.ApiResponse;
import com.datagenerator.ai.dto.ChatRequest;
import com.datagenerator.ai.dto.SessionInfo;
import com.datagenerator.ai.dto.SessionMessages;
import com.datagenerator.ai.dto.SessionMessages;
import com.datagenerator.ai.dto.SessionMessages.MessageItem;
import com.datagenerator.ai.service.AgentService;
import com.datagenerator.ai.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private AgentService agentService;

    @Mock
    private SessionService sessionService;

    private ObjectMapper mapper = new ObjectMapper();

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(agentService, sessionService, mapper);
    }

    // ── openChat ──

    @Test
    void openChat_shouldReturnChatId() {
        var response = controller.openChat();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).containsKey("chatId");
        assertThat(response.getBody().getData().get("chatId")).isNotBlank();
    }

    // ── chat (SSE) ──

    @Test
    void chat_shouldValidateEmptyChatId() {
        var flux = controller.chat("", new ChatRequest("hello", null));

        StepVerifier.create(flux)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                })
                .verifyComplete();
    }

    @Test
    void chat_shouldValidateBlankChatId() {
        var flux = controller.chat("   ", new ChatRequest("hello", null));

        StepVerifier.create(flux)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                })
                .verifyComplete();
    }

    @Test
    void chat_shouldValidateEmptyContent() {
        var flux = controller.chat("abc123", new ChatRequest("", null));

        StepVerifier.create(flux)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                })
                .verifyComplete();
    }

    @Test
    void chat_shouldDelegateToAgentService() {
        String chatId = "abc123";
        String content = "帮我创建用户表";

        var fakeEvent = ServerSentEvent.<String>builder()
                .event("test")
                .data("{}")
                .build();

        when(agentService.chat(chatId, content))
                .thenReturn(Flux.just(fakeEvent));

        var flux = controller.chat(chatId, new ChatRequest(content, null));

        StepVerifier.create(flux)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("test");
                })
                .verifyComplete();

        verify(agentService).chat(chatId, content);
    }

    // ── listSessions ──

    @Test
    void listSessions_shouldReturnSessions() {
        var sessions = List.of(
                new SessionInfo("abc123", "标题1", 5, "2026-07-10T15:00:00"),
                new SessionInfo("def456", "标题2", 3, "2026-07-10T14:00:00")
        );
        when(sessionService.listSessions()).thenReturn(sessions);

        var response = controller.listSessions();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).hasSize(2);
        assertThat(response.getBody().getData().get(0).chatId()).isEqualTo("abc123");
    }

    @Test
    void listSessions_shouldReturnEmptyList() {
        when(sessionService.listSessions()).thenReturn(List.of());

        var response = controller.listSessions();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEmpty();
    }

    // ── getMessages ──

    @Test
    void getMessages_shouldReturnMessages() {
        var messages = new SessionMessages("abc123", List.of(
                new MessageItem("user", List.of(new SessionMessages.TextBlock("帮我创建用户表"))),
                new MessageItem("assistant", List.of(new SessionMessages.TextBlock("好的")))
        ));
        when(sessionService.getMessages("abc123")).thenReturn(messages);

        var response = controller.getMessages("abc123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        var body = (ApiResponse<SessionMessages>) response.getBody();
        assertThat(body.getData().messages()).hasSize(2);
    }

    @Test
    void getMessages_shouldValidateEmptyChatId() {
        var response = controller.getMessages("");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // ── deleteChat ──

    @Test
    void deleteChat_shouldDeleteSession() {
        when(sessionService.deleteSession("abc123")).thenReturn(true);

        var response = controller.deleteChat("abc123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var body = (ApiResponse<Map<String, Object>>) response.getBody();
        assertThat(body.getData().get("deleted")).isEqualTo(true);
    }

    @Test
    void deleteChat_shouldReturnNotFoundStatus() {
        when(sessionService.deleteSession("not-found")).thenReturn(false);

        var response = controller.deleteChat("not-found");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var body = (ApiResponse<Map<String, Object>>) response.getBody();
        assertThat(body.getData().get("deleted")).isEqualTo(false);
    }

    @Test
    void deleteChat_shouldValidateEmptyChatId() {
        var response = controller.deleteChat("");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
