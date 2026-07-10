package com.datagenerator.ai.controller;

import com.datagenerator.ai.dto.ApiResponse;
import com.datagenerator.ai.dto.ChatRequest;
import com.datagenerator.ai.dto.SessionInfo;
import com.datagenerator.ai.dto.SessionMessages;
import com.datagenerator.ai.service.AgentService;
import com.datagenerator.ai.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/agent")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentService agentService;
    private final SessionService sessionService;
    private final ObjectMapper mapper;

    public ChatController(AgentService agentService, SessionService sessionService,
            ObjectMapper mapper) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.mapper = mapper;
    }

    @PostMapping("/chat/open")
    public ResponseEntity<ApiResponse<Map<String, String>>> openChat() {
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("chatId", UUID.randomUUID().toString())));
    }

    @PostMapping(value = "/chat/{chatId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable String chatId,
            @RequestBody ChatRequest request) {

        if (chatId == null || chatId.isBlank()) {
            return Flux.just(errorEvent("chatId 不能为空"));
        }
        if (request.content() == null || request.content().isBlank()) {
            return Flux.just(errorEvent("消息内容不能为空"));
        }

        log.info("对话开始: chatId={}", chatId);
        return agentService.chat(chatId, request.content());
    }

    // ==================== 会话管理端点 ====================

    /**
     * 列出所有会话及其摘要信息。
     */
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<SessionInfo>>> listSessions() {
        List<SessionInfo> sessions = sessionService.listSessions();
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    /**
     * 获取指定会话的消息历史。
     *
     * @param chatId 会话标识
     * @return 会话消息列表
     */
    @GetMapping("/chat/{chatId}/messages")
    public ResponseEntity<?> getMessages(
            @PathVariable String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("VALIDATION_ERROR", "chatId 不能为空"));
        }
        SessionMessages messages = sessionService.getMessages(chatId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    /**
     * 删除指定会话及其全部数据。
     *
     * @param chatId 会话标识
     * @return 操作结果
     */
    @DeleteMapping("/chat/{chatId}")
    public ResponseEntity<?> deleteChat(
            @PathVariable String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("VALIDATION_ERROR", "chatId 不能为空"));
        }
        boolean deleted = sessionService.deleteSession(chatId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "chatId", chatId,
                "deleted", deleted)));
    }

    private ServerSentEvent<String> errorEvent(String message) {
        try {
            return ServerSentEvent.<String>builder()
                    .event("error")
                    .data(mapper.writeValueAsString(Map.of("message", message)))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"message\":\"Internal error\"}")
                    .build();
        }
    }
}
