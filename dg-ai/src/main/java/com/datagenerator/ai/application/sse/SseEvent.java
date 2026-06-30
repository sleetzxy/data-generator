package com.datagenerator.ai.application.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** 内部 SSE 事件模型（非 REST DTO）。 */
public class SseEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String event;
    private final String data;

    public SseEvent(String event, String data) {
        this.event = event;
        this.data = data;
    }

    public String getEvent() {
        return event;
    }

    public String getData() {
        return data;
    }

    /** 流式 token 事件。 */
    public static SseEvent token(String delta) {
        return new SseEvent("token", json(Map.of("delta", delta != null ? delta : "")));
    }

    /** 工具执行事件（含可选元数据）。 */
    public static SseEvent tool(String name, String status, Map<String, Object> meta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name != null ? name : "");
        payload.put("status", status != null ? status : "");
        if (meta != null && !meta.isEmpty()) {
            payload.putAll(meta);
        }
        return new SseEvent("tool", json(payload));
    }

    /** 工具执行事件（无元数据便捷重载）。 */
    public static SseEvent tool(String name, String status) {
        return tool(name, status, null);
    }

    /** 流完成事件。 */
    public static SseEvent done(Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>(fields != null ? fields : Map.of());
        return new SseEvent("done", json(payload));
    }

    /** 流完成事件（无额外字段）。 */
    public static SseEvent done() {
        return done(Map.of());
    }

    /** 错误事件。 */
    public static SseEvent error(String code, String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("code", code != null ? code : "");
        payload.put("message", message != null ? message : "");
        return new SseEvent("error", json(payload));
    }

    /** 完全通用的事件构造入口。 */
    public static SseEvent event(String type, Map<String, Object> payload) {
        return new SseEvent(type, json(payload != null ? payload : Map.of()));
    }

    private static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize SSE payload", exception);
        }
    }
}
