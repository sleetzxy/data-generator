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
import java.time.Duration;
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
    private final ObjectMapper mapper;

    private final HarnessAgent agent;

    public AgentService(HarnessAgent agent, ObjectMapper mapper) {
        this.agent = agent;
        this.mapper = mapper;
    }

    /**
     * 执行 Agent 对话并返回 SSE 事件流。
     *
     * <p>直接使用 AgentScope 的 streamEvents API 返回的线性 Flux，
     * 不额外添加多播（share/refCount）或心跳（Flux.interval），
     * 遵循 AgentScope 框架设计：TCP 连接状态即活性信号，doFinally 处理清理。
     *
     * @param chatId  会话标识，同时作为 userId 和 sessionId
     * @param mode    模式（token / verbose）
     * @param content 用户消息文本
     * @return SSE 事件流
     */
    /** Agent 流空闲超时：若 180s 内无任何事件则判定为模型调用挂起 */
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(180);

    /**
     * 执行 Agent 对话并返回 SSE 事件流。
     *
     * <p>立即发射 connected 事件确认请求已被接受，避免前端误判为 NetworkError。
     * <br>空闲超时 180s：若模型调用挂起无任何事件，自动终止并发送 error，防止代理和浏览器一直阻塞。</p>
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

        return Flux.defer(() -> {
                    try {
                        return agent.streamEvents(userMsg, ctx);
                    } catch (Throwable e) {
                        log.error("Agent streamEvents 调用失败", e);
                        return Flux.<AgentEvent>error(e);
                    }
                })
                .doOnCancel(() -> log.warn("Agent SSE 流被取消: chatId={}", chatId))
                .flatMapSequential(event -> toSseEvents(event, verbose))
                .timeout(IDLE_TIMEOUT)
                .concatWithValues(AgentEventUtil.buildDoneEvent(mapper))
                .startWith(AgentEventUtil.ssEvent("connected",
                        "{\"chatId\":\"" + chatId + "\"}"))
                .onErrorResume(e -> {
                    try {
                        String msg = resolveUserErrorMessage(e);
                        log.error("Agent 执行异常: {}", msg, e);
                        // 直接发射 error + done 两个事件，不使用 onErrorComplete 兜底
                        // （onErrorComplete 会在 errorEvent 构建失败时静默吞掉整个 fallback，
                        //   导致前端仅收到 connected 就断连，显示"Error in input stream"）
                        return Flux.just(
                                AgentEventUtil.buildErrorEvent(mapper, msg),
                                AgentEventUtil.buildDoneEvent(mapper));
                    } catch (Throwable t) {
                        // 极端兜底：onErrorResume 自身也失败时，保证至少发出 error + done
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
     * <p>EXCEED_MAX_ITERS 作为普通 SSE error 事件发出，流正常完成后追加 done 事件。</p>
     */
    private Flux<ServerSentEvent<String>> toSseEvents(AgentEvent event, boolean verbose) {
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
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    String delta = ((ThinkingBlockDeltaEvent) event).getDelta();
                    yield Flux.just(
                            AgentEventUtil.ssEvent("thinking",
                                    mapper.writeValueAsString(Map.of("delta", delta))));
                }
                case THINKING_BLOCK_END -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    yield Flux.just(
                            AgentEventUtil.ssEvent("thinking",
                                    mapper.writeValueAsString(Map.of("end", true))));
                }

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

                case TOOL_RESULT_TEXT_DELTA -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                    yield Flux.just(
                            AgentEventUtil.ssEvent("observation",
                                    mapper.writeValueAsString(Map.of(
                                            "toolCallId", e.getToolCallId(),
                                            "delta", e.getDelta()))));
                }
                case TOOL_RESULT_END -> {
                    if (!verbose) {
                        yield Flux.empty();
                    }
                    yield Flux.just(
                            AgentEventUtil.ssEvent("observation",
                                    mapper.writeValueAsString(Map.of("end", true))));
                }

                case EXCEED_MAX_ITERS -> {
                    ExceedMaxItersEvent e = (ExceedMaxItersEvent) event;
                    // 发射普通 SSE error 事件后流正常完成（会追加 done 事件），
                    // 避免 Flux.error 信号与 Tomcat AsyncContext 交互产生 IllegalStateException
                    yield Flux.just(
                            AgentEventUtil.buildErrorEvent(mapper,
                                    "超过最大迭代次数: " + e.getMaxIters()));
                }

                // AGENT_END 和 AGENT_START 不产生 SSE 事件
                case AGENT_END, AGENT_START -> Flux.empty();

                // 未明确映射的事件类型：verbose 模式下作为日志输出
                default -> {
                    if (verbose) {
                        yield Flux.just(
                                AgentEventUtil.ssEvent("log",
                                        mapper.writeValueAsString(Map.of(
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
     * 将底层异常转为用户可读的错误提示，同时记录完整异常链便于排查。
     * <p>遍历 cause 链收集所有异常消息，避免只看到表层 IOException 而丢失深层 API 错误。</p>
     */
    private String resolveUserErrorMessage(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String fullChain = collectCauseChain(e);

        // 记录完整 cause 链到日志，方便排查模型 API 错误
        log.error("Agent 异常详情: rootMsg={}, fullChain={}", msg, fullChain, e);

        // 超过最大迭代
        if (msg.contains("超过最大迭代次数") || fullChain.contains("超过最大迭代次数")) {
            return msg.contains("超过最大迭代次数") ? msg : "超过最大迭代次数";
        }

        // 模型服务连接断开（Ollama / DeepSeek 通用）
        if (msg.contains("Connection reset") || msg.contains("connection refused")
                || msg.contains("SSE/NDJSON stream failed")
                || fullChain.contains("Connection reset") || fullChain.contains("connection refused")) {
            return "AI 模型服务连接中断，请稍后重试。"
                    + "如频繁发生，可检查模型服务状态或尝试缩短单次上下文长度";
        }

        // 超时（idle timeout 或 HTTP timeout）
        if (msg.contains("timeout") || msg.contains("Timeout")
                || fullChain.contains("timeout") || fullChain.contains("Timeout")) {
            return "模型调用超时。可能是上下文过长或模型服务负载过高，"
                    + "建议开启新会话重试或减少输入长度";
        }

        // HTTP 状态码相关（DeepSeek API 返回的 4xx/5xx）
        if (fullChain.contains("401") || fullChain.contains("403")) {
            return "模型 API 认证失败（401/403），请检查 api-key 是否有效";
        }
        if (fullChain.contains("429")) {
            return "模型 API 请求过于频繁（429），请稍后重试";
        }
        if (fullChain.contains("500") || fullChain.contains("502") || fullChain.contains("503")) {
            return "模型服务端异常（5xx），请稍后重试或联系管理员";
        }

        // 空响应
        if (msg.contains("empty completion") || msg.contains("no text")) {
            return "模型未生成有效回复，可能是上下文过长或请求过于复杂，建议开启新会话重试";
        }

        // 通用兜底：展示精简后的异常信息
        return "模型服务异常，请稍后重试。错误: " + (msg.length() > 80 ? msg.substring(0, 80) + "…" : msg);
    }

    /** 递归收集异常 cause 链中的所有消息，便于排查 DeepSeek API 等外部服务错误 */
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
                // 覆盖所有异常：JsonProcessingException、NPE、OOM 等
                return ssEvent("error", "{\"message\":\"内部错误\"}");
            }
        }
    }
}
