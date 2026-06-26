package com.datagenerator.ai.application.workflow;

import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.web.dto.common.SseEvent;
import java.util.function.Consumer;

/** 路由 + 执行：理解用户需求，选择 Agent 并发起对话。 */
public class AgentConversationWorkflow {

    private final AgentRoutingWorkflow routingWorkflow;
    private final AgentExecutionWorkflow executionWorkflow;

    public AgentConversationWorkflow(
            AgentRoutingWorkflow routingWorkflow, AgentExecutionWorkflow executionWorkflow) {
        this.routingWorkflow = routingWorkflow;
        this.executionWorkflow = executionWorkflow;
    }

    public void sendMessage(AgentSession session, String content, Consumer<SseEvent> emitter) {
        session.beginUserTurn(content);
        String agentId = routingWorkflow.resolveAgentId(session, content);
        executionWorkflow.execute(session, agentId, content, emitter);
    }
}
