package com.datagenerator.ai.application;

import com.datagenerator.ai.web.dto.common.SseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static SseEvent jobSaved() {
        return new SseEvent("job_saved", json(Map.of("status", "ok")));
    }

    public static SseEvent validationError(List<String> errors) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errors", errors != null ? errors : List.of());
        return new SseEvent("validation_error", json(payload));
    }

    public static SseEvent done(String messageId) {
        return done(messageId, false, false, false);
    }

    public static SseEvent done(
            String messageId, boolean draftIncomplete, boolean draftValidated, boolean hasDraft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId != null ? messageId : "");
        payload.put("draftIncomplete", draftIncomplete);
        payload.put("draftValidated", draftValidated);
        payload.put("hasDraft", hasDraft);
        return new SseEvent("done", json(payload));
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
