package com.datagenerator.ai.service;

import com.datagenerator.ai.dto.SseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SseEventFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseEventFactory() {
    }

    public static SseEvent token(String delta) {
        return new SseEvent("token", json(Map.of("delta", delta != null ? delta : "")));
    }

    public static SseEvent tool(String name, String status) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("name", name != null ? name : "");
        payload.put("status", status != null ? status : "");
        return new SseEvent("tool", json(payload));
    }

    public static SseEvent artifactYaml(String yaml) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("type", "yaml");
        payload.put("content", yaml != null ? yaml : "");
        return new SseEvent("artifact", json(payload));
    }

    public static SseEvent done(String messageId) {
        return new SseEvent("done", json(Map.of("messageId", messageId != null ? messageId : "")));
    }

    public static SseEvent error(String code, String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("code", code != null ? code : "");
        payload.put("message", message != null ? message : "");
        return new SseEvent("error", json(payload));
    }

    private static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize SSE payload", exception);
        }
    }
}
