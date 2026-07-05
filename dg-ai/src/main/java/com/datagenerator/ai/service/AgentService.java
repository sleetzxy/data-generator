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
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Agent 执行服务，将 HarnessAgent 的 streamEvents API 适配为 SSE 双模式输出。
 *
 * <p>token 模式：text、tool_start、tool_end、done、error
 * <br>verbose 模式：以上所有 + thinking、observation
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessAgent agent;

    public AgentService(HarnessAgent agent) {
        this.agent = agent;
    }

    /**
     * 执行 Agent 对话并返回 SSE 事件流。
     *
     * @param chatId  会话标识，同时作为 userId 和 sessionId
     * @param mode    模式（token / verbose）
     * @param content 用户消息文本
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> chat(String chatId, String mode, String content) {
        boolean verbose = "verbose".equalsIgnoreCase(mode);

        UserMessage userMsg = new UserMessage(chatId, content);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(chatId)
                .userId(chatId)
                .build();

        // 用 defer 包裹，确保同步异常也被 Reactive 链捕获
        return Flux.defer(() -> {
                    try {
                        return agent.streamEvents(userMsg, ctx);
                    } catch (Exception e) {
                        log.error("Agent streamEvents 调用失败", e);
                        return Flux.<AgentEvent>error(e);
                    }
                })
                .flatMapSequential(event -> toSseEvents(event, verbose))
                .concatWithValues(AgentEventUtil.buildDoneEvent())
                .onErrorResume(e -> {
                    log.error("Agent 执行异常: {}", e.getMessage());
                    return Flux.just(AgentEventUtil.buildErrorEvent(
                            e.getMessage() != null ? e.getMessage() : "模型服务不可用，请检查模型配置"));
                });
    }

    /**
     * 将 AgentEvent 转换为 SSE 事件流（可能为 0..N 个事件）。
     */
    private Flux<ServerSentEvent<String>> toSseEvents(AgentEvent event, boolean verbose) {
        try {
            log.debug("AgentEvent: type={}, class={}", event.getType(), event.getClass().getSimpleName());
            return switch (event.getType()) {
                case TEXT_BLOCK_DELTA -> {
                    String delta = ((TextBlockDeltaEvent) event).getDelta();
                    yield Flux.just(
                            AgentEventUtil.ssEvent("text",
                                    MAPPER.writeValueAsString(Map.of("delta", delta))));
                }
                case TEXT_BLOCK_END -> Flux.just(
                        AgentEventUtil.ssEvent("text",
                                MAPPER.writeValueAsString(Map.of("end", true))));

                case THINKING_BLOCK_DELTA -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    String delta = ((ThinkingBlockDeltaEvent) event).getDelta();
                    yield Flux.just(
                            AgentEventUtil.ssEvent("thinking",
                                    MAPPER.writeValueAsString(Map.of("delta", delta))));
                }
                case THINKING_BLOCK_END -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    yield Flux.just(
                            AgentEventUtil.ssEvent("thinking",
                                    MAPPER.writeValueAsString(Map.of("end", true))));
                }

                case TOOL_CALL_START -> {
                    ToolCallStartEvent e = (ToolCallStartEvent) event;
                    yield Flux.just(
                            AgentEventUtil.ssEvent("tool_start",
                                    MAPPER.writeValueAsString(Map.of(
                                            "tool", e.getToolCallName(),
                                            "toolCallId", e.getToolCallId()))));
                }
                case TOOL_CALL_END -> {
                    ToolCallEndEvent e = (ToolCallEndEvent) event;
                    yield Flux.just(
                            AgentEventUtil.ssEvent("tool_end",
                                    MAPPER.writeValueAsString(Map.of(
                                            "toolCallId", e.getToolCallId()))));
                }

                case TOOL_RESULT_TEXT_DELTA -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                    yield Flux.just(
                            AgentEventUtil.ssEvent("observation",
                                    MAPPER.writeValueAsString(Map.of(
                                            "toolCallId", e.getToolCallId(),
                                            "delta", e.getDelta()))));
                }
                case TOOL_RESULT_END -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    yield Flux.just(
                            AgentEventUtil.ssEvent("observation",
                                    MAPPER.writeValueAsString(Map.of("end", true))));
                }

                case EXCEED_MAX_ITERS -> {
                    ExceedMaxItersEvent e = (ExceedMaxItersEvent) event;
                    yield Flux.just(
                            AgentEventUtil.buildErrorEvent(
                                    "超过最大迭代次数: " + e.getMaxIters()));
                }

                // AGENT_END 和 AGENT_START 不产生 SSE 事件
                case AGENT_END, AGENT_START -> Flux.empty();

                // verbose 模式下将未明确映射的事件类型作为日志输出
                default -> {
                    if (verbose) {
                        yield Flux.just(
                                AgentEventUtil.ssEvent("log",
                                        MAPPER.writeValueAsString(Map.of(
                                                "type", event.getType().getValue(),
                                                "id", event.getId()))));
                    }
                    yield Flux.empty();
                }
            };
        } catch (JsonProcessingException e) {
            log.warn("事件序列化失败: type={}", event.getType(), e);
            return Flux.empty();
        }
    }

    /**
     * SSE 事件构造工具方法。
     */
    private static final class AgentEventUtil {

        static ServerSentEvent<String> ssEvent(String event, String data) {
            return ServerSentEvent.<String>builder()
                    .event(event)
                    .data(data)
                    .build();
        }

        static ServerSentEvent<String> buildDoneEvent() {
            try {
                return ssEvent("done",
                        MAPPER.writeValueAsString(Map.of(
                                "messageId", UUID.randomUUID().toString())));
            } catch (JsonProcessingException e) {
                return ssEvent("done",
                        "{\"messageId\":\"" + UUID.randomUUID() + "\"}");
            }
        }

        static ServerSentEvent<String> buildErrorEvent(String message) {
            try {
                return ssEvent("error",
                        MAPPER.writeValueAsString(Map.of(
                                "message", message != null ? message : "内部错误")));
            } catch (JsonProcessingException e) {
                return ssEvent("error", "{\"message\":\"内部错误\"}");
            }
        }
    }
}
