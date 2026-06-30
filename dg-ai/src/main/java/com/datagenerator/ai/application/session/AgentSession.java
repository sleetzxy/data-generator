package com.datagenerator.ai.application.session;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentSession {

    private final String sessionId;
    private final String agentId;
    private final String provider;
    private final Instant createdAt;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
    /** 是否已有进行中的 SSE 对话轮次。 */
    private final AtomicBoolean turnInProgress = new AtomicBoolean(false);
    /** 当前轮次是否已被取消（DELETE 会话或客户端断开 SSE）。 */
    private final AtomicBoolean turnCancelled = new AtomicBoolean(false);
    private Instant lastActiveAt;

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);
    /** 当前活跃的流式连接句柄，用于外部取消。 */
    private volatile StreamingHandle streamingHandle;

    public AgentSession(
            String sessionId, String agentId, String provider, Instant createdAt) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.provider = provider;
        this.createdAt = createdAt;
        this.lastActiveAt = createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    /** 通用扩展上下文：允许按 key 存放任意会话级扩展数据。 */
    public void putContext(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        attributes.put(key.trim(), value);
    }

    public Object getContext(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return attributes.get(key.trim());
    }

    public <T> T getContext(String key, Class<T> type) {
        Object value = getContext(key);
        if (value == null || type == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
    }

    public void removeContext(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        attributes.remove(key.trim());
    }

    public Map<String, Object> getAllContext() {
        return Map.copyOf(attributes);
    }

    public void beginUserTurn(String userMessage) {
        // no-op: session package保持通用，不处理业务语义字段
    }

    /** 尝试占用对话轮次；已有进行中的轮次时返回 false。 */
    public boolean tryBeginTurn() {
        return turnInProgress.compareAndSet(false, true);
    }

    /** 释放对话轮次占用并清除取消标志。 */
    public void endTurn() {
        clearStreamingHandle();
        turnInProgress.set(false);
        turnCancelled.set(false);
    }

    /** 标记当前轮次应停止（不释放占用，由 endTurn 统一释放）。 */
    public void requestTurnCancellation() {
        turnCancelled.set(true);
    }

    /** 注册流式连接句柄。 */
    public void attachStreamingHandle(StreamingHandle handle) {
        this.streamingHandle = handle;
    }

    /** 取消当前流式连接。 */
    public void cancelStream() {
        StreamingHandle h = this.streamingHandle;
        if (h != null && !h.isCancelled()) {
            h.cancel();
            log.info("Cancelled streaming handle for session {}", sessionId);
        }
    }

    /** 清理流式句柄引用，若连接仍活跃则先取消。 */
    public void clearStreamingHandle() {
        StreamingHandle h = this.streamingHandle;
        if (h != null && !h.isCancelled()) {
            h.cancel();
        }
        this.streamingHandle = null;
    }

    public boolean isTurnCancelled() {
        return turnCancelled.get();
    }

    public boolean isTurnInProgress() {
        return turnInProgress.get();
    }

}

