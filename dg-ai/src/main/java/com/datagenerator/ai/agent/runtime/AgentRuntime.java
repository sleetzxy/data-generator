package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.web.dto.common.SseEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import java.util.function.Consumer;

/** 单个 Agent 的运行时：LangChain4j 实例、续写与结构化输出处理。Tool Set 由配置绑定。 */
public interface AgentRuntime {

    String agentId();

    TokenStream chat(String sessionId, String userMessage, AgentExecutionContext context);

    /** @return 是否需要自动续写结构化输出 */
    boolean onComplete(AgentSession session, ChatResponse response, Consumer<SseEvent> emitter);

    void evictSession(String sessionId);
}
