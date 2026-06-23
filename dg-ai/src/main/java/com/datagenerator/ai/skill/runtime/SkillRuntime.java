package com.datagenerator.ai.skill.runtime;

import com.datagenerator.ai.dto.SseEvent;
import com.datagenerator.ai.config.ChatModelFactory;
import com.datagenerator.ai.session.AgentSession;
import com.datagenerator.ai.session.ChatMemoryStore;
import com.datagenerator.ai.skill.SkillCatalog;
import dev.langchain4j.service.TokenStream;
import java.util.function.Consumer;

/**
 * 单个 Skill 的运行时绑定：LangChain4j Agent、Tool 与 artifact 完成处理。
 */
public interface SkillRuntime {

    String skillId();

    TokenStream chat(String sessionId, String userMessage, SkillExecutionContext context);

    void onComplete(AgentSession session, String fullText, Consumer<SseEvent> emitter);

    void evictSession(String sessionId);
}
