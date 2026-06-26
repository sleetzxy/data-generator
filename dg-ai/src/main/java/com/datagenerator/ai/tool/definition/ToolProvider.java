package com.datagenerator.ai.tool.definition;

import com.datagenerator.ai.application.AgentIoLogger;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.Map;

/** 按 Tool Set 提供可共享的 Tool 组合与 LangChain4j Executor 映射。 */
public interface ToolProvider {

    String toolSetId();

    Map<ToolSpecification, ToolExecutor> createExecutors(AgentIoLogger ioLogger);
}
