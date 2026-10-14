package com.datagenerator.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Agent 执行服务，将 HarnessAgent 的 streamEvents API 适配为 SSE 输出。
 *
 * <p>事件：text、thinking、tool_start、tool_end、observation、done、error。
 * 工具 JSON 结果由前端在 observation.end 时通用地缩进展示。
 *
 * <p>会话状态通过 AgentScope 的 AgentStateStore 自动持久化。
 */
@Service
public class AgentService {

    /** 统一的默认用户 ID，所有 Web 控制台会话共享此命名空间。 */
    public static final String DEFAULT_USER_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private final ObjectMapper mapper;

    private final HarnessAgent agent;

    /** 攒齐 TEXT_DELTA，避免 TOOL_RESULT_END 时 StateStore 尚未落盘导致 result 为空 */
    private final ConcurrentHashMap<String, StringBuilder> pendingToolResults =
            new ConcurrentHashMap<>();

    public AgentService(HarnessAgent agent, ObjectMapper mapper) {
        this.agent = agent;
        this.mapper = mapper;
    }

    /** Agent 流空闲超时：若 180s 内无任何事件则判定为模型调用挂起 */
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(180);

    /**
     * 执行 Agent 对话并返回 SSE 事件流。
     *
     * @param chatId  会话标识（即 sessionId），由前端在 /chat/open 时获取
     * @param content 用户消息文本
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> chat(String chatId, String content) {
        UserMessage userMsg = new UserMessage(chatId, content);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(chatId)
                .userId(DEFAULT_USER_ID)
                .build();

        return Flux.defer(() -> {
                    try {
                        return agent.streamEvents(userMsg, ctx);
                    } catch (Throwable e) {
                        log.error("Agent streamEvents 调用失败", e);
                        return Flux.<AgentEvent>error(e);
                    }
                })
                .doOnCancel(() -> log.warn("Agent SSE 流被取消: chatId={}", chatId))
                .flatMapSequential(event -> toSseEvents(event, chatId))
                .timeout(IDLE_TIMEOUT)
                .concatWith(Flux.just(AgentEventUtil.buildDoneEvent(mapper)))
                .startWith(AgentEventUtil.ssEvent("connected",
                        "{\"chatId\":\"" + chatId + "\"}"))
                .onErrorResume(e -> {
                    try {
                        String msg = resolveUserErrorMessage(e);
                        log.error("Agent 执行异常: {}", msg, e);
                        return Flux.just(
                                AgentEventUtil.buildErrorEvent(mapper, msg),
                                AgentEventUtil.buildDoneEvent(mapper));
                    } catch (Throwable t) {
                        log.error("onErrorResume 处理器自身异常", t);
                        return Flux.just(
                                AgentEventUtil.ssEvent("error",
                                        "{\"message\":\"内部错误\"}"),
                                AgentEventUtil.ssEvent("done",
                                        "{\"messageId\":\"" + UUID.randomUUID() + "\"}"));
                    }
                });
    }

    /**
     * 将 AgentEvent 转换为 SSE 事件流（可能为 0..N 个事件）。
     */
    private Flux<ServerSentEvent<String>> toSseEvents(AgentEvent event, String chatId) {
        try {
            log.debug("AgentEvent: type={}, class={}", event.getType(), event.getClass().getSimpleName());
            return switch (event.getType()) {
                case TEXT_BLOCK_DELTA -> {
                    String delta = ((TextBlockDeltaEvent) event).getDelta();
                    yield Flux.just(
                            AgentEventUtil.ssEvent("text",
                                    mapper.writeValueAsString(Map.of("delta", delta))));
                }
                case TEXT_BLOCK_END -> Flux.just(
                        AgentEventUtil.ssEvent("text",
                                mapper.writeValueAsString(Map.of("end", true))));

                case THINKING_BLOCK_DELTA -> {
                    String delta = ((ThinkingBlockDeltaEvent) event).getDelta();
                    yield Flux.just(
                            AgentEventUtil.ssEvent("thinking",
                                    mapper.writeValueAsString(Map.of("delta", delta))));
                }
                case THINKING_BLOCK_END -> Flux.just(
                        AgentEventUtil.ssEvent("thinking",
                                mapper.writeValueAsString(Map.of("end", true))));

                case TOOL_CALL_START -> {
                    ToolCallStartEvent e = (ToolCallStartEvent) event;
                    yield Flux.just(
                            AgentEventUtil.ssEvent("tool_start",
                                    mapper.writeValueAsString(Map.of(
                                            "tool", e.getToolCallName(),
                                            "toolCallId", e.getToolCallId()))));
                }
                case TOOL_CALL_END -> {
                    ToolCallEndEvent e = (ToolCallEndEvent) event;
                    yield Flux.just(
                            AgentEventUtil.ssEvent("tool_end",
                                    mapper.writeValueAsString(Map.of(
                                            "toolCallId", e.getToolCallId()))));
                }

                case TOOL_RESULT_START -> {
                    ToolResultStartEvent e = (ToolResultStartEvent) event;
                    pendingToolResults.putIfAbsent(e.getToolCallId(), new StringBuilder());
                    yield Flux.just(
                            AgentEventUtil.ssEvent("tool_start",
                                    mapper.writeValueAsString(Map.of(
                                            "tool", e.getToolCallName(),
                                            "toolCallId", e.getToolCallId()))));
                }
                case TOOL_RESULT_TEXT_DELTA -> {
                    ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                    String delta = e.getDelta();
                    if (delta != null && !delta.isEmpty()) {
                        pendingToolResults
                                .computeIfAbsent(e.getToolCallId(), id -> new StringBuilder())
                                .append(delta);
                    }
                    yield Flux.just(
                            AgentEventUtil.ssEvent("observation",
                                    mapper.writeValueAsString(Map.of(
                                            "toolCallId", e.getToolCallId(),
                                            "delta", e.getDelta()))));
                }
                case TOOL_RESULT_END -> {
                    ToolResultEndEvent e = (ToolResultEndEvent) event;
                    String toolCallId = e.getToolCallId();
                    StringBuilder pending = pendingToolResults.remove(toolCallId);
                    String result = pending != null && !pending.isEmpty()
                            ? pending.toString()
                            : extractToolResultFromState(chatId, toolCallId);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("toolCallId", toolCallId);
                    data.put("end", true);
                    if (!result.isEmpty()) {
                        data.put("result", result);
                    }
                    // JSON 等结果由前端在工具区通用地美化展示
                    yield Flux.just(
                            AgentEventUtil.ssEvent("observation",
                                    mapper.writeValueAsString(data)));
                }

                case EXCEED_MAX_ITERS -> {
                    ExceedMaxItersEvent e = (ExceedMaxItersEvent) event;
                    yield Flux.just(
                            AgentEventUtil.buildErrorEvent(mapper,
                                    "超过最大迭代次数: " + e.getMaxIters()));
                }

                case AGENT_END, AGENT_START -> Flux.empty();

                default -> Flux.just(
                        AgentEventUtil.ssEvent("log",
                                mapper.writeValueAsString(Map.of(
                                        "type", event.getType().getValue(),
                                        "id", event.getId()))));
            };
        } catch (JsonProcessingException e) {
            log.warn("事件序列化失败: type={}", event.getType(), e);
            return Flux.empty();
        }
    }

    /**
     * 从 AgentStateStore 中读取指定 toolCallId 对应的工具结果文本（兜底）。
     */
    private String extractToolResultFromState(String chatId, String toolCallId) {
        try {
            var store = agent.getStateStore();
            if (store == null) {
                return "";
            }
            var stateOpt = store
                    .get(DEFAULT_USER_ID, chatId, "agent_state",
                            io.agentscope.core.state.AgentState.class);
            if (stateOpt.isEmpty()) {
                return "";
            }
            var ctx = stateOpt.get().getContext();
            for (int i = ctx.size() - 1; i >= 0; i--) {
                var msg = ctx.get(i);
                for (var block : msg.getContent()) {
                    if (block instanceof io.agentscope.core.message.ToolResultBlock trb
                            && toolCallId.equals(trb.getId())) {
                        StringBuilder sb = new StringBuilder();
                        for (var outBlock : trb.getOutput()) {
                            if (outBlock instanceof io.agentscope.core.message.TextBlock tb) {
                                String t = tb.getText();
                                if (t != null && !t.isBlank()) {
                                    if (!sb.isEmpty()) {
                                        sb.append('\n');
                                    }
                                    sb.append(t);
                                }
                            }
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("读取工具结果失败: toolCallId={}", toolCallId, ex);
        }
        return "";
    }

    private String resolveUserErrorMessage(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String fullChain = collectCauseChain(e);

        log.error("Agent 异常详情: rootMsg={}, fullChain={}", msg, fullChain, e);

        if (msg.contains("超过最大迭代次数") || fullChain.contains("超过最大迭代次数")) {
            return msg.contains("超过最大迭代次数") ? msg : "超过最大迭代次数";
        }

        if (msg.contains("Connection reset") || msg.contains("connection refused")
                || msg.contains("SSE/NDJSON stream failed")
                || fullChain.contains("Connection reset") || fullChain.contains("connection refused")) {
            return "AI 模型服务连接中断，请稍后重试。"
                    + "如频繁发生，可检查模型服务状态或尝试缩短单次上下文长度";
        }

        if (msg.contains("timeout") || msg.contains("Timeout")
                || fullChain.contains("timeout") || fullChain.contains("Timeout")) {
            return "模型调用超时。可能是上下文过长或模型服务负载过高，"
                    + "建议开启新会话重试或减少输入长度";
        }

        if (fullChain.contains("401") || fullChain.contains("403")) {
            return "模型 API 认证失败（401/403），请检查 api-key 是否有效";
        }
        if (fullChain.contains("429")) {
            return "模型 API 请求过于频繁（429），请稍后重试";
        }
        if (fullChain.contains("500") || fullChain.contains("502") || fullChain.contains("503")) {
            return "模型服务端异常（5xx），请稍后重试或联系管理员";
        }

        if (msg.contains("empty completion") || msg.contains("no text")) {
            return "模型未生成有效回复，可能是上下文过长或请求过于复杂，建议开启新会话重试";
        }

        return "模型服务异常，请稍后重试。错误: " + (msg.length() > 80 ? msg.substring(0, 80) + "…" : msg);
    }

    private String collectCauseChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            if (!sb.isEmpty()) {
                sb.append(" ← ");
            }
            String m = current.getMessage();
            sb.append(current.getClass().getSimpleName());
            if (m != null && !m.isBlank()) {
                sb.append(": ").append(m);
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private static final class AgentEventUtil {

        static ServerSentEvent<String> ssEvent(String event, String data) {
            return ServerSentEvent.<String>builder()
                    .event(event)
                    .data(data)
                    .build();
        }

        static ServerSentEvent<String> buildDoneEvent(ObjectMapper mapper) {
            try {
                return ssEvent("done",
                        mapper.writeValueAsString(Map.of(
                                "messageId", UUID.randomUUID().toString())));
            } catch (Throwable e) {
                return ssEvent("done",
                        "{\"messageId\":\"" + UUID.randomUUID() + "\"}");
            }
        }

        static ServerSentEvent<String> buildErrorEvent(ObjectMapper mapper, String message) {
            try {
                String safeMsg = message != null ? message : "内部错误";
                return ssEvent("error",
                        mapper.writeValueAsString(Map.of("message", safeMsg)));
            } catch (Throwable e) {
                return ssEvent("error", "{\"message\":\"内部错误\"}");
            }
        }
    }
}
