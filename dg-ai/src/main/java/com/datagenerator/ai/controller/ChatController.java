package com.datagenerator.ai.controller;

import com.datagenerator.ai.dto.ApiResponse;
import com.datagenerator.ai.dto.ChatRequest;
import com.datagenerator.ai.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/agent")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentService agentService;
    private final ObjectMapper mapper;

    public ChatController(AgentService agentService, ObjectMapper mapper) {
        this.agentService = agentService;
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
            @RequestParam(defaultValue = "token") String mode,
            @RequestBody ChatRequest request) {

        if (chatId == null || chatId.isBlank()) {
            return Flux.just(errorEvent("chatId 不能为空"));
        }
        if (request.content() == null || request.content().isBlank()) {
            return Flux.just(errorEvent("消息内容不能为空"));
        }

        log.info("对话开始: chatId={}, mode={}", chatId, mode);
        return agentService.chat(chatId, mode, request.content());
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
