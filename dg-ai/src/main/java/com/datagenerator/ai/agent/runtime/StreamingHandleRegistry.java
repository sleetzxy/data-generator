package com.datagenerator.ai.agent.runtime;

import dev.langchain4j.model.chat.response.StreamingHandle;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按 sessionId 持有活跃流式连接的 {@link StreamingHandle}，供会话取消时调用原生 cancel()。 */
public class StreamingHandleRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamingHandleRegistry.class);

    private final ConcurrentHashMap<String, StreamingHandle> handles = new ConcurrentHashMap<>();

    public void register(String sessionId, StreamingHandle handle) {
        if (sessionId == null || handle == null) {
            return;
        }
        handles.put(sessionId, handle);
    }

    public void cancel(String sessionId) {
        StreamingHandle handle = handles.remove(sessionId);
        if (handle == null) {
            return;
        }
        if (!handle.isCancelled()) {
            handle.cancel();
            log.info("Cancelled streaming handle for session {}", sessionId);
        }
    }

    public void unregister(String sessionId) {
        if (sessionId != null) {
            handles.remove(sessionId);
        }
    }
}
