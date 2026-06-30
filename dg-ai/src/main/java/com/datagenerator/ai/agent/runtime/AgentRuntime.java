package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.sse.SseEvent;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.prompt.AgentPrompt;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.Map;
import java.util.function.Consumer;

/** 单个 Agent 的运行时：LangChain4j 实例与最终收敛处理。Tool Set 由配置绑定。 */
public interface AgentRuntime {

    String agentId();

    /** 提供此 Agent 的 Tool 执行器映射。 */
    Map<ToolSpecification, ToolExecutor> createToolExecutors(AgentIoLogger ioLogger);

    TokenStream chat(
            String sessionId,
            String userMessage,
            String provider,
            AiProperties aiProperties,
            ChatMemoryStore chatMemoryStore,
            AgentPrompt promptProvider);

    /** 流式过程中每次工具执行完成时的回调。默认发送通用 tool 事件，Agent 可按需覆写。 */
    default void onToolExecuted(String sessionId, ToolExecution tool, Consumer<SseEvent> emitter) {
        String name = tool.request() != null ? tool.request().name() : "unknown";
        emitter.accept(SseEvent.tool(name, "done"));
    }

    /** 对模型完整回复做收敛处理（如提取并校验 YAML 草稿）。 */
    boolean onComplete(AgentSession session, ChatResponse response, Consumer<SseEvent> emitter);

    void evictSession(String sessionId);
}
